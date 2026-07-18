package main

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type masterStore struct {
	pool *pgxpool.Pool
}

func openMasterStore(ctx context.Context, databaseURL string) (*masterStore, error) {
	if databaseURL == "" {
		return nil, errors.New("database URL is required")
	}
	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse database URL: %w", err)
	}
	config.MaxConns = 8
	config.MinConns = 1
	config.MaxConnLifetime = time.Hour
	config.MaxConnIdleTime = 15 * time.Minute
	config.HealthCheckPeriod = time.Minute
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, fmt.Errorf("connect master DB: %w", err)
	}
	store := &masterStore{pool: pool}
	if err := store.ensureSchema(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return store, nil
}

func (store *masterStore) Close() {
	if store.pool != nil {
		store.pool.Close()
	}
}

const schemaDDL = `
CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS policies (
    policy_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    department_id TEXT NOT NULL DEFAULT '',
    default_action TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS firewall_rules (
    rule_id TEXT PRIMARY KEY,
    policy_id TEXT NOT NULL,
    priority INTEGER NOT NULL,
    rule_type TEXT NOT NULL,
    action TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    regex_pattern TEXT NOT NULL DEFAULT '',
    source_ip_group_key TEXT NOT NULL DEFAULT '',
    start_time_utc TEXT NOT NULL DEFAULT '',
    end_time_utc TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS source_ip_groups (
    group_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    department_id TEXT NOT NULL DEFAULT '',
    group_key TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS source_ip_group_members (
    group_id TEXT NOT NULL,
    source_ipv4 TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (group_id, source_ipv4)
);
`

const policiesTable = "policies"
const rulesTable = "firewall_rules"
const groupsTable = "source_ip_groups"
const membersTable = "source_ip_group_members"

func (store *masterStore) ensureSchema(ctx context.Context) error {
	if _, err := store.pool.Exec(ctx, schemaDDL); err != nil {
		return fmt.Errorf("create schema: %w", err)
	}
	_, err := store.pool.Exec(ctx, "INSERT INTO metadata(key, value) VALUES ('revision', '0') ON CONFLICT (key) DO NOTHING")
	return err
}

func (store *masterStore) ensureImported(ctx context.Context, dataDirectory string) error {
	datasetVersion, err := readDatasetVersion(dataDirectory)
	if err != nil {
		return err
	}
	initialized, existingVersion, err := store.readImportState(ctx)
	if err != nil {
		return err
	}
	if initialized {
		if existingVersion != datasetVersion {
			return fmt.Errorf("master DB contains dataset %q, but input manifest is %q; use a new database to preserve maintained data", existingVersion, datasetVersion)
		}
		return nil
	}
	if err := store.resetForImport(ctx, datasetVersion); err != nil {
		return err
	}
	source := csvMasterSource{directory: dataDirectory}
	if err := store.importPolicies(ctx, source); err != nil {
		return fmt.Errorf("import policies: %w", err)
	}
	if err := store.importRules(ctx, source); err != nil {
		return fmt.Errorf("import rules: %w", err)
	}
	if err := store.importGroups(ctx, source); err != nil {
		return fmt.Errorf("import source IP groups: %w", err)
	}
	if err := store.importMembers(ctx, source); err != nil {
		return fmt.Errorf("import source IP group members: %w", err)
	}
	_, err = store.pool.Exec(ctx, "INSERT INTO metadata(key, value) VALUES ('initialized', 'true') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")
	return err
}

func (store *masterStore) readImportState(ctx context.Context) (bool, string, error) {
	var initialized, version string
	err := store.pool.QueryRow(ctx, "SELECT value FROM metadata WHERE key = 'initialized'").Scan(&initialized)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, "", nil
		}
		return false, "", err
	}
	err = store.pool.QueryRow(ctx, "SELECT value FROM metadata WHERE key = 'dataset_version'").Scan(&version)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return false, "", err
	}
	return initialized == "true", version, nil
}

func (store *masterStore) resetForImport(ctx context.Context, datasetVersion string) error {
	tx, err := store.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	for _, table := range []string{policiesTable, rulesTable, groupsTable, membersTable} {
		if _, err := tx.Exec(ctx, fmt.Sprintf("TRUNCATE TABLE %s", table)); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(ctx, "INSERT INTO metadata(key, value) VALUES ('dataset_version', $1) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", datasetVersion); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, "INSERT INTO metadata(key, value) VALUES ('revision', '0') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, "INSERT INTO metadata(key, value) VALUES ('initialized', 'false') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (store *masterStore) importPolicies(ctx context.Context, source csvMasterSource) error {
	rows := make([][]any, 0, 4096)
	if err := source.readPolicies(func(row []string) error {
		enabled, err := strconv.ParseBool(row[4])
		if err != nil {
			return err
		}
		rows = append(rows, []any{row[0], row[1], row[2], row[3], enabled, row[5], row[6]})
		return nil
	}); err != nil {
		return err
	}
	_, err := store.pool.CopyFrom(ctx, pgx.Identifier{policiesTable}, []string{"policy_id", "tenant_id", "department_id", "default_action", "enabled", "created_at", "updated_at"}, pgx.CopyFromRows(rows))
	return err
}

func (store *masterStore) importRules(ctx context.Context, source csvMasterSource) error {
	rows := make([][]any, 0, 8192)
	if err := source.readRules(func(row []string) error {
		priority, err := strconv.Atoi(row[2])
		if err != nil {
			return err
		}
		enabled, err := strconv.ParseBool(row[5])
		if err != nil {
			return err
		}
		rows = append(rows, []any{row[0], row[1], priority, row[3], row[4], enabled, row[6], row[7], row[8], row[9], row[10], row[11]})
		return nil
	}); err != nil {
		return err
	}
	_, err := store.pool.CopyFrom(ctx, pgx.Identifier{rulesTable}, []string{"rule_id", "policy_id", "priority", "rule_type", "action", "enabled", "regex_pattern", "source_ip_group_key", "start_time_utc", "end_time_utc", "created_at", "updated_at"}, pgx.CopyFromRows(rows))
	return err
}

func (store *masterStore) importGroups(ctx context.Context, source csvMasterSource) error {
	rows := make([][]any, 0, 4096)
	if err := source.readGroups(func(row []string) error {
		rows = append(rows, []any{row[0], row[1], row[2], row[3], row[4], row[5], row[6]})
		return nil
	}); err != nil {
		return err
	}
	_, err := store.pool.CopyFrom(ctx, pgx.Identifier{groupsTable}, []string{"group_id", "tenant_id", "department_id", "group_key", "name", "created_at", "updated_at"}, pgx.CopyFromRows(rows))
	return err
}

func (store *masterStore) importMembers(ctx context.Context, source csvMasterSource) error {
	rows := make([][]any, 0, 8192)
	if err := source.readMembers(func(row []string) error {
		rows = append(rows, []any{row[0], row[1], row[2], row[3]})
		return nil
	}); err != nil {
		return err
	}
	_, err := store.pool.CopyFrom(ctx, pgx.Identifier{membersTable}, []string{"group_id", "source_ipv4", "created_at", "updated_at"}, pgx.CopyFromRows(rows))
	return err
}

func metadataValue(ctx context.Context, query interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}, key string) (string, error) {
	var value string
	err := query.QueryRow(ctx, "SELECT value FROM metadata WHERE key = $1", key).Scan(&value)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", nil
		}
		return "", err
	}
	return value, nil
}

func (store *masterStore) dataVersion() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	initialized, err := metadataValue(ctx, store.pool, "initialized")
	if err != nil {
		return "", err
	}
	if initialized != "true" {
		return "", errors.New("master DB import is incomplete")
	}
	dataset, err := metadataValue(ctx, store.pool, "dataset_version")
	if err != nil {
		return "", err
	}
	revisionText, err := metadataValue(ctx, store.pool, "revision")
	if err != nil {
		return "", err
	}
	revision, err := strconv.ParseInt(revisionText, 10, 64)
	if err != nil {
		return "", fmt.Errorf("invalid revision %q: %w", revisionText, err)
	}
	if revision == 0 {
		return dataset, nil
	}
	return fmt.Sprintf("%s+r%d", dataset, revision), nil
}

func readMasterRows(ctx context.Context, query interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
}, sql string, scan func(pgx.Rows) ([]string, error), consume func([]string) error) error {
	rows, err := query.Query(ctx, sql)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		row, err := scan(rows)
		if err != nil {
			return err
		}
		if err := consume(row); err != nil {
			return err
		}
	}
	return rows.Err()
}

func (store *masterStore) readPolicies(consume func([]string) error) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	return readMasterRows(ctx, store.pool,
		"SELECT policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at FROM policies",
		func(rows pgx.Rows) ([]string, error) {
			var id, tenantID, departmentID, action, createdAt, updatedAt string
			var enabled bool
			if err := rows.Scan(&id, &tenantID, &departmentID, &action, &enabled, &createdAt, &updatedAt); err != nil {
				return nil, err
			}
			return []string{id, tenantID, departmentID, action, strconv.FormatBool(enabled), createdAt, updatedAt}, nil
		}, consume)
}

func (store *masterStore) readRules(consume func([]string) error) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	return readMasterRows(ctx, store.pool,
		"SELECT rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at FROM firewall_rules",
		func(rows pgx.Rows) ([]string, error) {
			var id, policyID, ruleType, action, regex, groupKey, startTime, endTime, createdAt, updatedAt string
			var priority int
			var enabled bool
			if err := rows.Scan(&id, &policyID, &priority, &ruleType, &action, &enabled, &regex, &groupKey, &startTime, &endTime, &createdAt, &updatedAt); err != nil {
				return nil, err
			}
			return []string{id, policyID, strconv.Itoa(priority), ruleType, action, strconv.FormatBool(enabled), regex, groupKey, startTime, endTime, createdAt, updatedAt}, nil
		}, consume)
}

func (store *masterStore) readGroups(consume func([]string) error) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	return readMasterRows(ctx, store.pool,
		"SELECT group_id, tenant_id, department_id, group_key, name, created_at, updated_at FROM source_ip_groups",
		func(rows pgx.Rows) ([]string, error) {
			var id, tenantID, departmentID, key, name, createdAt, updatedAt string
			if err := rows.Scan(&id, &tenantID, &departmentID, &key, &name, &createdAt, &updatedAt); err != nil {
				return nil, err
			}
			return []string{id, tenantID, departmentID, key, name, createdAt, updatedAt}, nil
		}, consume)
}

func (store *masterStore) readMembers(consume func([]string) error) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	return readMasterRows(ctx, store.pool,
		"SELECT group_id, source_ipv4, created_at, updated_at FROM source_ip_group_members",
		func(rows pgx.Rows) ([]string, error) {
			var groupID, address, createdAt, updatedAt string
			if err := rows.Scan(&groupID, &address, &createdAt, &updatedAt); err != nil {
				return nil, err
			}
			return []string{groupID, address, createdAt, updatedAt}, nil
		}, consume)
}

func LoadEngineFromMasterStore(ctx context.Context, store *masterStore) (*Engine, error) {
	dataVersion, err := store.dataVersion()
	if err != nil {
		return nil, err
	}
	engine, err := loadEngine(store)
	if err != nil {
		return nil, err
	}
	engine.DataVersion = dataVersion
	return engine, nil
}

// txMasterSource reads master data from an in-flight pgx.Tx so that admin mutations
// can validate the post-mutation snapshot before commit. It implements masterSource.
type txMasterSource struct {
	tx          pgx.Tx
	ctx         context.Context
	cachedData  string
	cachedError error
	resolved    bool
}

func (source *txMasterSource) dataVersion() (string, error) {
	if source.resolved {
		return source.cachedData, source.cachedError
	}
	source.resolved = true
	initialized, err := metadataValue(source.ctx, source.tx, "initialized")
	if err != nil {
		source.cachedError = err
		return source.cachedData, err
	}
	if initialized != "true" {
		source.cachedError = errors.New("master DB import is incomplete")
		return source.cachedData, source.cachedError
	}
	dataset, err := metadataValue(source.ctx, source.tx, "dataset_version")
	if err != nil {
		source.cachedError = err
		return source.cachedData, err
	}
	revisionText, err := metadataValue(source.ctx, source.tx, "revision")
	if err != nil {
		source.cachedError = err
		return source.cachedData, err
	}
	revision, err := strconv.ParseInt(revisionText, 10, 64)
	if err != nil {
		source.cachedError = fmt.Errorf("invalid revision %q: %w", revisionText, err)
		return source.cachedData, source.cachedError
	}
	if revision == 0 {
		source.cachedData = dataset
	} else {
		source.cachedData = fmt.Sprintf("%s+r%d", dataset, revision)
	}
	return source.cachedData, nil
}

func (source *txMasterSource) readPolicies(consume func([]string) error) error {
	return readMasterRows(source.ctx, source.tx,
		"SELECT policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at FROM policies",
		func(rows pgx.Rows) ([]string, error) {
			var id, tenantID, departmentID, action, createdAt, updatedAt string
			var enabled bool
			if err := rows.Scan(&id, &tenantID, &departmentID, &action, &enabled, &createdAt, &updatedAt); err != nil {
				return nil, err
			}
			return []string{id, tenantID, departmentID, action, strconv.FormatBool(enabled), createdAt, updatedAt}, nil
		}, consume)
}

func (source *txMasterSource) readRules(consume func([]string) error) error {
	return readMasterRows(source.ctx, source.tx,
		"SELECT rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at FROM firewall_rules",
		func(rows pgx.Rows) ([]string, error) {
			var id, policyID, ruleType, action, regex, groupKey, startTime, endTime, createdAt, updatedAt string
			var priority int
			var enabled bool
			if err := rows.Scan(&id, &policyID, &priority, &ruleType, &action, &enabled, &regex, &groupKey, &startTime, &endTime, &createdAt, &updatedAt); err != nil {
				return nil, err
			}
			return []string{id, policyID, strconv.Itoa(priority), ruleType, action, strconv.FormatBool(enabled), regex, groupKey, startTime, endTime, createdAt, updatedAt}, nil
		}, consume)
}

func (source *txMasterSource) readGroups(consume func([]string) error) error {
	return readMasterRows(source.ctx, source.tx,
		"SELECT group_id, tenant_id, department_id, group_key, name, created_at, updated_at FROM source_ip_groups",
		func(rows pgx.Rows) ([]string, error) {
			var id, tenantID, departmentID, key, name, createdAt, updatedAt string
			if err := rows.Scan(&id, &tenantID, &departmentID, &key, &name, &createdAt, &updatedAt); err != nil {
				return nil, err
			}
			return []string{id, tenantID, departmentID, key, name, createdAt, updatedAt}, nil
		}, consume)
}

func (source *txMasterSource) readMembers(consume func([]string) error) error {
	return readMasterRows(source.ctx, source.tx,
		"SELECT group_id, source_ipv4, created_at, updated_at FROM source_ip_group_members",
		func(rows pgx.Rows) ([]string, error) {
			var groupID, address, createdAt, updatedAt string
			if err := rows.Scan(&groupID, &address, &createdAt, &updatedAt); err != nil {
				return nil, err
			}
			return []string{groupID, address, createdAt, updatedAt}, nil
		}, consume)
}

// loadEngineFromTx constructs an Engine inside a transaction so callers can validate a mutation before commit.
func loadEngineFromTx(ctx context.Context, tx pgx.Tx) (*Engine, error) {
	source := &txMasterSource{tx: tx, ctx: ctx}
	dataVersion, err := source.dataVersion()
	if err != nil {
		return nil, err
	}
	engine, err := loadEngine(source)
	if err != nil {
		return nil, err
	}
	engine.DataVersion = dataVersion
	return engine, nil
}

func waitForMaster(ctx context.Context, databaseURL string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	var lastErr error
	for time.Now().Before(deadline) {
		config, err := pgxpool.ParseConfig(databaseURL)
		if err != nil {
			return err
		}
		config.MaxConns = 1
		config.MinConns = 0
		pool, err := pgxpool.NewWithConfig(ctx, config)
		if err != nil {
			lastErr = err
			time.Sleep(500 * time.Millisecond)
			continue
		}
		pingCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
		err = pool.Ping(pingCtx)
		cancel()
		pool.Close()
		if err == nil {
			return nil
		}
		lastErr = err
		time.Sleep(500 * time.Millisecond)
	}
	if lastErr == nil {
		lastErr = errors.New("master DB unreachable")
	}
	return fmt.Errorf("master DB not ready within %s: %w", timeout, lastErr)
}
