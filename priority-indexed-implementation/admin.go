package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	bolt "go.etcd.io/bbolt"
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

func getJSONRecord[T any](store *masterStore, bucketName, key []byte) (T, error) {
	var record T
	err := store.db.View(func(tx *bolt.Tx) error {
		bucket := tx.Bucket(bucketName)
		if bucket == nil {
			return errMasterNotFound
		}
		value := bucket.Get(key)
		if value == nil {
			return errMasterNotFound
		}
		return json.Unmarshal(value, &record)
	})
	return record, err
}

func (service *masterService) replace(bucketName, key, value []byte, remove bool) error {
	service.mutex.Lock()
	defer service.mutex.Unlock()

	var oldValue []byte
	var oldRevision uint64
	var existed bool
	err := service.store.db.Update(func(tx *bolt.Tx) error {
		bucket := tx.Bucket(bucketName)
		metadata := tx.Bucket(metadataBucket)
		if bucket == nil || metadata == nil {
			return errors.New("master DB schema is incomplete")
		}
		if previous := bucket.Get(key); previous != nil {
			existed = true
			oldValue = append([]byte(nil), previous...)
		}
		if remove {
			if !existed {
				return errMasterNotFound
			}
			if err := bucket.Delete(key); err != nil {
				return err
			}
		} else if err := bucket.Put(key, value); err != nil {
			return err
		}
		oldRevision = readRevision(metadata)
		return metadata.Put(revisionKey, revisionBytes(oldRevision+1))
	})
	if err != nil {
		return err
	}

	engine, loadErr := LoadEngineFromMasterStore(service.store)
	if loadErr == nil {
		service.engine.engine.Store(engine)
		return nil
	}
	rollbackErr := service.store.db.Update(func(tx *bolt.Tx) error {
		bucket := tx.Bucket(bucketName)
		if existed {
			if err := bucket.Put(key, oldValue); err != nil {
				return err
			}
		} else if err := bucket.Delete(key); err != nil {
			return err
		}
		return tx.Bucket(metadataBucket).Put(revisionKey, revisionBytes(oldRevision))
	})
	if rollbackErr != nil {
		return fmt.Errorf("master update invalid (%v), and rollback failed: %w", loadErr, rollbackErr)
	}
	return fmt.Errorf("master update rejected: %w", loadErr)
}

func recordTimes(created string) (string, string) {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if created == "" {
		created = now
	}
	return created, now
}

func (service *masterService) putPolicy(id string, record policyRecord) error {
	if id == "" || (record.PolicyID != "" && record.PolicyID != id) {
		return errors.New("policy_id must match the URL")
	}
	if old, err := getJSONRecord[policyRecord](service.store, policiesBucket, []byte(id)); err == nil && record.CreatedAt == "" {
		record.CreatedAt = old.CreatedAt
	}
	record.PolicyID = id
	record.CreatedAt, record.UpdatedAt = recordTimes(record.CreatedAt)
	value, err := json.Marshal(record)
	if err != nil {
		return err
	}
	return service.replace(policiesBucket, []byte(id), value, false)
}

func (service *masterService) putRule(id string, record ruleRecord) error {
	if id == "" || (record.RuleID != "" && record.RuleID != id) {
		return errors.New("rule_id must match the URL")
	}
	if old, err := getJSONRecord[ruleRecord](service.store, rulesBucket, []byte(id)); err == nil && record.CreatedAt == "" {
		record.CreatedAt = old.CreatedAt
	}
	record.RuleID = id
	record.CreatedAt, record.UpdatedAt = recordTimes(record.CreatedAt)
	value, err := json.Marshal(record)
	if err != nil {
		return err
	}
	return service.replace(rulesBucket, []byte(id), value, false)
}

func (service *masterService) putGroup(id string, record groupRecord) error {
	if id == "" || (record.GroupID != "" && record.GroupID != id) {
		return errors.New("group_id must match the URL")
	}
	if old, err := getJSONRecord[groupRecord](service.store, groupsBucket, []byte(id)); err == nil && record.CreatedAt == "" {
		record.CreatedAt = old.CreatedAt
	}
	record.GroupID = id
	record.CreatedAt, record.UpdatedAt = recordTimes(record.CreatedAt)
	value, err := json.Marshal(record)
	if err != nil {
		return err
	}
	return service.replace(groupsBucket, []byte(id), value, false)
}

func getMember(store *masterStore, groupID, address string) (memberRecord, error) {
	var record memberRecord
	err := store.db.View(func(tx *bolt.Tx) error {
		bucket := tx.Bucket(membersBucket)
		if bucket == nil {
			return errMasterNotFound
		}
		value := bucket.Get(memberKey(groupID, address))
		if value == nil {
			return errMasterNotFound
		}
		var stored memberValue
		if err := json.Unmarshal(value, &stored); err != nil {
			return err
		}
		record = memberRecord{GroupID: groupID, SourceIPv4: address, CreatedAt: stored.CreatedAt, UpdatedAt: stored.UpdatedAt}
		return nil
	})
	return record, err
}

func (service *masterService) putMember(groupID, address string, record memberRecord) error {
	if groupID == "" || address == "" || (record.GroupID != "" && record.GroupID != groupID) || (record.SourceIPv4 != "" && record.SourceIPv4 != address) {
		return errors.New("group_id and source_ipv4 must match the URL")
	}
	if old, err := getMember(service.store, groupID, address); err == nil && record.CreatedAt == "" {
		record.CreatedAt = old.CreatedAt
	}
	record.GroupID, record.SourceIPv4 = groupID, address
	record.CreatedAt, record.UpdatedAt = recordTimes(record.CreatedAt)
	value, err := json.Marshal(memberValue{CreatedAt: record.CreatedAt, UpdatedAt: record.UpdatedAt})
	if err != nil {
		return err
	}
	return service.replace(membersBucket, memberKey(groupID, address), value, false)
}

func registerAdminHandlers(mux *http.ServeMux, service *masterService) {
	mux.HandleFunc("GET /v1/admin/policies/{id}", func(writer http.ResponseWriter, request *http.Request) {
		record, err := getJSONRecord[policyRecord](service.store, policiesBucket, []byte(request.PathValue("id")))
		writeAdminResult(writer, record, err)
	})
	mux.HandleFunc("PUT /v1/admin/policies/{id}", func(writer http.ResponseWriter, request *http.Request) {
		var record policyRecord
		if !decodeAdminBody(writer, request, &record) {
			return
		}
		writeAdminMutation(writer, service.putPolicy(request.PathValue("id"), record))
	})
	mux.HandleFunc("DELETE /v1/admin/policies/{id}", func(writer http.ResponseWriter, request *http.Request) {
		writeAdminMutation(writer, service.replace(policiesBucket, []byte(request.PathValue("id")), nil, true))
	})

	mux.HandleFunc("GET /v1/admin/rules/{id}", func(writer http.ResponseWriter, request *http.Request) {
		record, err := getJSONRecord[ruleRecord](service.store, rulesBucket, []byte(request.PathValue("id")))
		writeAdminResult(writer, record, err)
	})
	mux.HandleFunc("PUT /v1/admin/rules/{id}", func(writer http.ResponseWriter, request *http.Request) {
		var record ruleRecord
		if !decodeAdminBody(writer, request, &record) {
			return
		}
		writeAdminMutation(writer, service.putRule(request.PathValue("id"), record))
	})
	mux.HandleFunc("DELETE /v1/admin/rules/{id}", func(writer http.ResponseWriter, request *http.Request) {
		writeAdminMutation(writer, service.replace(rulesBucket, []byte(request.PathValue("id")), nil, true))
	})

	mux.HandleFunc("GET /v1/admin/source-ip-groups/{id}", func(writer http.ResponseWriter, request *http.Request) {
		record, err := getJSONRecord[groupRecord](service.store, groupsBucket, []byte(request.PathValue("id")))
		writeAdminResult(writer, record, err)
	})
	mux.HandleFunc("PUT /v1/admin/source-ip-groups/{id}", func(writer http.ResponseWriter, request *http.Request) {
		var record groupRecord
		if !decodeAdminBody(writer, request, &record) {
			return
		}
		writeAdminMutation(writer, service.putGroup(request.PathValue("id"), record))
	})
	mux.HandleFunc("DELETE /v1/admin/source-ip-groups/{id}", func(writer http.ResponseWriter, request *http.Request) {
		writeAdminMutation(writer, service.replace(groupsBucket, []byte(request.PathValue("id")), nil, true))
	})

	mux.HandleFunc("GET /v1/admin/source-ip-groups/{id}/members/{source_ipv4}", func(writer http.ResponseWriter, request *http.Request) {
		record, err := getMember(service.store, request.PathValue("id"), request.PathValue("source_ipv4"))
		writeAdminResult(writer, record, err)
	})
	mux.HandleFunc("PUT /v1/admin/source-ip-groups/{id}/members/{source_ipv4}", func(writer http.ResponseWriter, request *http.Request) {
		var record memberRecord
		if !decodeAdminBody(writer, request, &record) {
			return
		}
		writeAdminMutation(writer, service.putMember(request.PathValue("id"), request.PathValue("source_ipv4"), record))
	})
	mux.HandleFunc("DELETE /v1/admin/source-ip-groups/{id}/members/{source_ipv4}", func(writer http.ResponseWriter, request *http.Request) {
		writeAdminMutation(writer, service.replace(membersBucket, memberKey(request.PathValue("id"), request.PathValue("source_ipv4")), nil, true))
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
