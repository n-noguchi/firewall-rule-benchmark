package main

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5"
)

var errMasterNotFound = errors.New("master record not found")

type engineHolder struct{ engine atomic.Pointer[Engine] }

func newEngineHolder(engine *Engine) *engineHolder {
	holder := &engineHolder{}
	holder.engine.Store(engine)
	return holder
}

func (holder *engineHolder) Load() *Engine { return holder.engine.Load() }

type masterService struct {
	store  *masterStore
	engine *engineHolder
	mutex  sync.Mutex
}

func newMasterService(store *masterStore, holder *engineHolder) *masterService {
	return &masterService{store: store, engine: holder}
}

func getPolicyRecord(ctx context.Context, store *masterStore, id string) (policyRecord, error) {
	var record policyRecord
	err := store.pool.QueryRow(ctx, "SELECT policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at FROM policies WHERE policy_id = $1", id).Scan(
		&record.PolicyID, &record.TenantID, &record.DepartmentID, &record.DefaultAction, &record.Enabled, &record.CreatedAt, &record.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return policyRecord{}, errMasterNotFound
	}
	return record, err
}

func getRuleRecord(ctx context.Context, store *masterStore, id string) (ruleRecord, error) {
	var record ruleRecord
	err := store.pool.QueryRow(ctx, "SELECT rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at FROM firewall_rules WHERE rule_id = $1", id).Scan(
		&record.RuleID, &record.PolicyID, &record.Priority, &record.RuleType, &record.Action, &record.Enabled, &record.RegexPattern, &record.SourceIPGroupKey, &record.StartTimeUTC, &record.EndTimeUTC, &record.CreatedAt, &record.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return ruleRecord{}, errMasterNotFound
	}
	return record, err
}

func getGroupRecord(ctx context.Context, store *masterStore, id string) (groupRecord, error) {
	var record groupRecord
	err := store.pool.QueryRow(ctx, "SELECT group_id, tenant_id, department_id, group_key, name, created_at, updated_at FROM source_ip_groups WHERE group_id = $1", id).Scan(
		&record.GroupID, &record.TenantID, &record.DepartmentID, &record.GroupKey, &record.Name, &record.CreatedAt, &record.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return groupRecord{}, errMasterNotFound
	}
	return record, err
}

func getMemberRecord(ctx context.Context, store *masterStore, groupID, address string) (memberRecord, error) {
	var record memberRecord
	err := store.pool.QueryRow(ctx, "SELECT group_id, source_ipv4, created_at, updated_at FROM source_ip_group_members WHERE group_id = $1 AND source_ipv4 = $2", groupID, address).Scan(
		&record.GroupID, &record.SourceIPv4, &record.CreatedAt, &record.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return memberRecord{}, errMasterNotFound
	}
	return record, err
}

func recordTimes(created string) (string, string) {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if created == "" {
		created = now
	}
	return created, now
}

// mutationSpec describes a single-row upsert/delete against a master table.
// columns and args describe the new row; keys describes the predicate columns
// (e.g. ("policy_id") or ("group_id", "source_ipv4")).
type mutationSpec struct {
	table   string
	columns []string
	args    []any
	keys    []string
	keyArgs []any
	remove  bool
}

// runMutation executes the spec inside a transaction, bumps the revision,
// validates the resulting snapshot by building the Engine inside the same
// transaction, commits, then swaps the live Engine. On validation failure the
// transaction is rolled back and the live Engine is untouched.
func (service *masterService) runMutation(ctx context.Context, spec mutationSpec) error {
	service.mutex.Lock()
	defer service.mutex.Unlock()

	tx, err := service.store.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if spec.remove {
		existed, err := rowExists(ctx, tx, spec.table, spec.keys, spec.keyArgs)
		if err != nil {
			return err
		}
		if !existed {
			return errMasterNotFound
		}
		if _, err := tx.Exec(ctx, deleteSQL(spec.table, spec.keys), spec.keyArgs...); err != nil {
			return err
		}
	} else {
		if _, err := tx.Exec(ctx, upsertSQL(spec.table, spec.columns, spec.keys), spec.args...); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(ctx, "UPDATE metadata SET value = (CAST(value AS BIGINT) + 1)::TEXT WHERE key = 'revision'"); err != nil {
		return err
	}

	// Validate the post-mutation state inside the transaction before commit so a
	// malformed master never replaces the live Engine.
	engine, err := loadEngineFromTx(ctx, tx)
	if err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	service.engine.engine.Store(engine)
	return nil
}

func rowExists(ctx context.Context, tx pgx.Tx, table string, keys []string, keyArgs []any) (bool, error) {
	predicate := whereClause(keys, 1)
	var exists bool
	err := tx.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM "+table+" WHERE "+predicate+")", keyArgs...).Scan(&exists)
	return exists, err
}

func stringSliceToAny(values []string) []any {
	result := make([]any, len(values))
	for index, value := range values {
		result[index] = value
	}
	return result
}

// whereClause builds "col1 = $start, col2 = $(start+1), ..." for the predicate.
func whereClause(keys []string, start int) string {
	var builder strings.Builder
	for index, key := range keys {
		if index > 0 {
			builder.WriteString(" AND ")
		}
		builder.WriteString(key)
		builder.WriteString(" = $")
		builder.WriteString(strconv.Itoa(start + index))
	}
	return builder.String()
}

func deleteSQL(table string, keys []string) string {
	return "DELETE FROM " + table + " WHERE " + whereClause(keys, 1)
}

func upsertSQL(table string, columns, keys []string) string {
	placeholders := make([]string, len(columns))
	for index := range columns {
		placeholders[index] = "$" + strconv.Itoa(index+1)
	}
	conflictColumns := strings.Join(keys, ", ")
	updateClause := ""
	for _, column := range columns {
		if containsString(keys, column) {
			continue
		}
		if updateClause != "" {
			updateClause += ", "
		}
		updateClause += column + " = EXCLUDED." + column
	}
	cols := strings.Join(columns, ", ")
	vals := strings.Join(placeholders, ", ")
	if updateClause == "" {
		return "INSERT INTO " + table + "(" + cols + ") VALUES (" + vals + ") ON CONFLICT DO NOTHING"
	}
	return "INSERT INTO " + table + "(" + cols + ") VALUES (" + vals + ") ON CONFLICT (" + conflictColumns + ") DO UPDATE SET " + updateClause
}

func containsString(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}

func (service *masterService) putPolicy(ctx context.Context, id string, record policyRecord) error {
	if id == "" || (record.PolicyID != "" && record.PolicyID != id) {
		return errors.New("policy_id must match the URL")
	}
	if old, err := getPolicyRecord(ctx, service.store, id); err == nil && record.CreatedAt == "" {
		record.CreatedAt = old.CreatedAt
	}
	record.PolicyID = id
	record.CreatedAt, record.UpdatedAt = recordTimes(record.CreatedAt)
	return service.runMutation(ctx, mutationSpec{
		table:   policiesTable,
		columns: []string{"policy_id", "tenant_id", "department_id", "default_action", "enabled", "created_at", "updated_at"},
		args:    []any{record.PolicyID, record.TenantID, record.DepartmentID, record.DefaultAction, record.Enabled, record.CreatedAt, record.UpdatedAt},
		keys:    []string{"policy_id"},
		keyArgs: []any{id},
	})
}

func (service *masterService) putRule(ctx context.Context, id string, record ruleRecord) error {
	if id == "" || (record.RuleID != "" && record.RuleID != id) {
		return errors.New("rule_id must match the URL")
	}
	if old, err := getRuleRecord(ctx, service.store, id); err == nil && record.CreatedAt == "" {
		record.CreatedAt = old.CreatedAt
	}
	record.RuleID = id
	record.CreatedAt, record.UpdatedAt = recordTimes(record.CreatedAt)
	return service.runMutation(ctx, mutationSpec{
		table:   rulesTable,
		columns: []string{"rule_id", "policy_id", "priority", "rule_type", "action", "enabled", "regex_pattern", "source_ip_group_key", "start_time_utc", "end_time_utc", "created_at", "updated_at"},
		args:    []any{record.RuleID, record.PolicyID, record.Priority, record.RuleType, record.Action, record.Enabled, record.RegexPattern, record.SourceIPGroupKey, record.StartTimeUTC, record.EndTimeUTC, record.CreatedAt, record.UpdatedAt},
		keys:    []string{"rule_id"},
		keyArgs: []any{id},
	})
}

func (service *masterService) putGroup(ctx context.Context, id string, record groupRecord) error {
	if id == "" || (record.GroupID != "" && record.GroupID != id) {
		return errors.New("group_id must match the URL")
	}
	if old, err := getGroupRecord(ctx, service.store, id); err == nil && record.CreatedAt == "" {
		record.CreatedAt = old.CreatedAt
	}
	record.GroupID = id
	record.CreatedAt, record.UpdatedAt = recordTimes(record.CreatedAt)
	return service.runMutation(ctx, mutationSpec{
		table:   groupsTable,
		columns: []string{"group_id", "tenant_id", "department_id", "group_key", "name", "created_at", "updated_at"},
		args:    []any{record.GroupID, record.TenantID, record.DepartmentID, record.GroupKey, record.Name, record.CreatedAt, record.UpdatedAt},
		keys:    []string{"group_id"},
		keyArgs: []any{id},
	})
}

func (service *masterService) putMember(ctx context.Context, groupID, address string, record memberRecord) error {
	if groupID == "" || address == "" || (record.GroupID != "" && record.GroupID != groupID) || (record.SourceIPv4 != "" && record.SourceIPv4 != address) {
		return errors.New("group_id and source_ipv4 must match the URL")
	}
	if old, err := getMemberRecord(ctx, service.store, groupID, address); err == nil && record.CreatedAt == "" {
		record.CreatedAt = old.CreatedAt
	}
	record.GroupID, record.SourceIPv4 = groupID, address
	record.CreatedAt, record.UpdatedAt = recordTimes(record.CreatedAt)
	return service.runMutation(ctx, mutationSpec{
		table:   membersTable,
		columns: []string{"group_id", "source_ipv4", "created_at", "updated_at"},
		args:    []any{record.GroupID, record.SourceIPv4, record.CreatedAt, record.UpdatedAt},
		keys:    []string{"group_id", "source_ipv4"},
		keyArgs: []any{groupID, address},
	})
}

func (service *masterService) deletePolicy(ctx context.Context, id string) error {
	return service.runMutation(ctx, mutationSpec{table: policiesTable, keys: []string{"policy_id"}, keyArgs: []any{id}, remove: true})
}

func (service *masterService) deleteRule(ctx context.Context, id string) error {
	return service.runMutation(ctx, mutationSpec{table: rulesTable, keys: []string{"rule_id"}, keyArgs: []any{id}, remove: true})
}

func (service *masterService) deleteGroup(ctx context.Context, id string) error {
	return service.runMutation(ctx, mutationSpec{table: groupsTable, keys: []string{"group_id"}, keyArgs: []any{id}, remove: true})
}

func (service *masterService) deleteMember(ctx context.Context, groupID, address string) error {
	return service.runMutation(ctx, mutationSpec{table: membersTable, keys: []string{"group_id", "source_ipv4"}, keyArgs: []any{groupID, address}, remove: true})
}

func registerAdminHandlers(mux *http.ServeMux, service *masterService) {
	mux.HandleFunc("GET /v1/admin/policies/{id}", func(writer http.ResponseWriter, request *http.Request) {
		record, err := getPolicyRecord(request.Context(), service.store, request.PathValue("id"))
		writeAdminResult(writer, record, err)
	})
	mux.HandleFunc("PUT /v1/admin/policies/{id}", func(writer http.ResponseWriter, request *http.Request) {
		var record policyRecord
		if !decodeAdminBody(writer, request, &record) {
			return
		}
		writeAdminMutation(writer, service.putPolicy(request.Context(), request.PathValue("id"), record))
	})
	mux.HandleFunc("DELETE /v1/admin/policies/{id}", func(writer http.ResponseWriter, request *http.Request) {
		writeAdminMutation(writer, service.deletePolicy(request.Context(), request.PathValue("id")))
	})

	mux.HandleFunc("GET /v1/admin/rules/{id}", func(writer http.ResponseWriter, request *http.Request) {
		record, err := getRuleRecord(request.Context(), service.store, request.PathValue("id"))
		writeAdminResult(writer, record, err)
	})
	mux.HandleFunc("PUT /v1/admin/rules/{id}", func(writer http.ResponseWriter, request *http.Request) {
		var record ruleRecord
		if !decodeAdminBody(writer, request, &record) {
			return
		}
		writeAdminMutation(writer, service.putRule(request.Context(), request.PathValue("id"), record))
	})
	mux.HandleFunc("DELETE /v1/admin/rules/{id}", func(writer http.ResponseWriter, request *http.Request) {
		writeAdminMutation(writer, service.deleteRule(request.Context(), request.PathValue("id")))
	})

	mux.HandleFunc("GET /v1/admin/source-ip-groups/{id}", func(writer http.ResponseWriter, request *http.Request) {
		record, err := getGroupRecord(request.Context(), service.store, request.PathValue("id"))
		writeAdminResult(writer, record, err)
	})
	mux.HandleFunc("PUT /v1/admin/source-ip-groups/{id}", func(writer http.ResponseWriter, request *http.Request) {
		var record groupRecord
		if !decodeAdminBody(writer, request, &record) {
			return
		}
		writeAdminMutation(writer, service.putGroup(request.Context(), request.PathValue("id"), record))
	})
	mux.HandleFunc("DELETE /v1/admin/source-ip-groups/{id}", func(writer http.ResponseWriter, request *http.Request) {
		writeAdminMutation(writer, service.deleteGroup(request.Context(), request.PathValue("id")))
	})

	mux.HandleFunc("GET /v1/admin/source-ip-groups/{id}/members/{source_ipv4}", func(writer http.ResponseWriter, request *http.Request) {
		record, err := getMemberRecord(request.Context(), service.store, request.PathValue("id"), request.PathValue("source_ipv4"))
		writeAdminResult(writer, record, err)
	})
	mux.HandleFunc("PUT /v1/admin/source-ip-groups/{id}/members/{source_ipv4}", func(writer http.ResponseWriter, request *http.Request) {
		var record memberRecord
		if !decodeAdminBody(writer, request, &record) {
			return
		}
		writeAdminMutation(writer, service.putMember(request.Context(), request.PathValue("id"), request.PathValue("source_ipv4"), record))
	})
	mux.HandleFunc("DELETE /v1/admin/source-ip-groups/{id}/members/{source_ipv4}", func(writer http.ResponseWriter, request *http.Request) {
		writeAdminMutation(writer, service.deleteMember(request.Context(), request.PathValue("id"), request.PathValue("source_ipv4")))
	})
}

func decodeAdminBody(writer http.ResponseWriter, request *http.Request, target any) bool {
	defer request.Body.Close()
	if err := decodeOneJSON(http.MaxBytesReader(writer, request.Body, 1<<20), target); err != nil {
		writeJSON(writer, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return false
	}
	return true
}

func writeAdminResult(writer http.ResponseWriter, result any, err error) {
	if err == nil {
		writeJSON(writer, http.StatusOK, result)
		return
	}
	status := http.StatusInternalServerError
	if errors.Is(err, errMasterNotFound) {
		status = http.StatusNotFound
	}
	writeJSON(writer, status, map[string]string{"error": err.Error()})
}

func writeAdminMutation(writer http.ResponseWriter, err error) {
	if err == nil {
		writeJSON(writer, http.StatusOK, map[string]string{"status": "applied"})
		return
	}
	status := http.StatusConflict
	if errors.Is(err, errMasterNotFound) {
		status = http.StatusNotFound
	}
	writeJSON(writer, status, map[string]string{"error": err.Error()})
}
