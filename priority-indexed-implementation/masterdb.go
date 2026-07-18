package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"

	bolt "go.etcd.io/bbolt"
)

var (
	metadataBucket = []byte("metadata")
	policiesBucket = []byte("policies")
	rulesBucket    = []byte("rules")
	groupsBucket   = []byte("source_ip_groups")
	membersBucket  = []byte("source_ip_group_members")
	allBuckets     = [][]byte{metadataBucket, policiesBucket, rulesBucket, groupsBucket, membersBucket}

	initializedKey = []byte("initialized")
	datasetKey     = []byte("dataset_version")
	revisionKey    = []byte("revision")
)

const memberKeySeparator byte = 0

type policyRecord struct {
	PolicyID      string `json:"policy_id"`
	TenantID      string `json:"tenant_id"`
	DepartmentID  string `json:"department_id"`
	DefaultAction string `json:"default_action"`
	Enabled       bool   `json:"enabled"`
	CreatedAt     string `json:"created_at"`
	UpdatedAt     string `json:"updated_at"`
}

type ruleRecord struct {
	RuleID           string `json:"rule_id"`
	PolicyID         string `json:"policy_id"`
	Priority         int    `json:"priority"`
	RuleType         string `json:"rule_type"`
	Action           string `json:"action"`
	Enabled          bool   `json:"enabled"`
	RegexPattern     string `json:"regex_pattern"`
	SourceIPGroupKey string `json:"source_ip_group_key"`
	StartTimeUTC     string `json:"start_time_utc"`
	EndTimeUTC       string `json:"end_time_utc"`
	CreatedAt        string `json:"created_at"`
	UpdatedAt        string `json:"updated_at"`
}

type groupRecord struct {
	GroupID      string `json:"group_id"`
	TenantID     string `json:"tenant_id"`
	DepartmentID string `json:"department_id"`
	GroupKey     string `json:"group_key"`
	Name         string `json:"name"`
	CreatedAt    string `json:"created_at"`
	UpdatedAt    string `json:"updated_at"`
}

type memberRecord struct {
	GroupID    string `json:"group_id"`
	SourceIPv4 string `json:"source_ipv4"`
	CreatedAt  string `json:"created_at"`
	UpdatedAt  string `json:"updated_at"`
}

type memberValue struct {
	CreatedAt string `json:"created_at"`
	UpdatedAt string `json:"updated_at"`
}

type masterStore struct{ db *bolt.DB }

func openMasterStore(path string) (*masterStore, error) {
	if path == "" {
		return nil, errors.New("master DB path is required")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("create master DB directory: %w", err)
	}
	db, err := bolt.Open(path, 0o600, &bolt.Options{Timeout: 10 * time.Second, NoFreelistSync: true})
	if err != nil {
		return nil, fmt.Errorf("open master DB: %w", err)
	}
	return &masterStore{db: db}, nil
}

func (store *masterStore) Close() error { return store.db.Close() }

func (store *masterStore) ensureImported(dataDirectory string) error {
	datasetVersion, err := readDatasetVersion(dataDirectory)
	if err != nil {
		return err
	}
	var initialized, existingVersion string
	if err := store.db.View(func(tx *bolt.Tx) error {
		bucket := tx.Bucket(metadataBucket)
		if bucket != nil {
			initialized = string(bucket.Get(initializedKey))
			existingVersion = string(bucket.Get(datasetKey))
		}
		return nil
	}); err != nil {
		return err
	}
	if initialized == "true" {
		if existingVersion != datasetVersion {
			return fmt.Errorf("master DB contains dataset %q, but input manifest is %q; use a new DB path to preserve maintained data", existingVersion, datasetVersion)
		}
		return nil
	}
	if err := store.resetForImport(datasetVersion); err != nil {
		return err
	}
	source := csvMasterSource{directory: dataDirectory}
	if err := store.importPolicies(source); err != nil {
		return fmt.Errorf("import policies: %w", err)
	}
	if err := store.importRules(source); err != nil {
		return fmt.Errorf("import rules: %w", err)
	}
	if err := store.importGroups(source); err != nil {
		return fmt.Errorf("import source IP groups: %w", err)
	}
	if err := store.importMembers(source); err != nil {
		return fmt.Errorf("import source IP group members: %w", err)
	}
	return store.db.Update(func(tx *bolt.Tx) error {
		return tx.Bucket(metadataBucket).Put(initializedKey, []byte("true"))
	})
}

func (store *masterStore) resetForImport(datasetVersion string) error {
	return store.db.Update(func(tx *bolt.Tx) error {
		for _, name := range allBuckets {
			if tx.Bucket(name) != nil {
				if err := tx.DeleteBucket(name); err != nil {
					return err
				}
			}
			if _, err := tx.CreateBucket(name); err != nil {
				return err
			}
		}
		metadata := tx.Bucket(metadataBucket)
		if err := metadata.Put(datasetKey, []byte(datasetVersion)); err != nil {
			return err
		}
		return metadata.Put(revisionKey, revisionBytes(0))
	})
}

type importPair struct{ key, value []byte }

func (store *masterStore) importRows(bucket []byte, read func(func([]string) error) error, convert func([]string) ([]byte, []byte, error)) error {
	const batchSize = 10000
	batch := make([]importPair, 0, batchSize)
	flush := func() error {
		if len(batch) == 0 {
			return nil
		}
		err := store.db.Update(func(tx *bolt.Tx) error {
			target := tx.Bucket(bucket)
			for _, pair := range batch {
				if target.Get(pair.key) != nil {
					return fmt.Errorf("duplicate master key %q", pair.key)
				}
				if err := target.Put(pair.key, pair.value); err != nil {
					return err
				}
			}
			return nil
		})
		batch = batch[:0]
		return err
	}
	if err := read(func(row []string) error {
		key, value, err := convert(row)
		if err != nil {
			return err
		}
		batch = append(batch, importPair{key: key, value: value})
		if len(batch) == batchSize {
			return flush()
		}
		return nil
	}); err != nil {
		return err
	}
	return flush()
}

func (store *masterStore) importPolicies(source csvMasterSource) error {
	return store.importRows(policiesBucket, source.readPolicies, func(row []string) ([]byte, []byte, error) {
		enabled, err := strconv.ParseBool(row[4])
		if err != nil {
			return nil, nil, err
		}
		record := policyRecord{row[0], row[1], row[2], row[3], enabled, row[5], row[6]}
		value, err := json.Marshal(record)
		return []byte(record.PolicyID), value, err
	})
}

func (store *masterStore) importRules(source csvMasterSource) error {
	return store.importRows(rulesBucket, source.readRules, func(row []string) ([]byte, []byte, error) {
		priority, err := strconv.Atoi(row[2])
		if err != nil {
			return nil, nil, err
		}
		enabled, err := strconv.ParseBool(row[5])
		if err != nil {
			return nil, nil, err
		}
		record := ruleRecord{row[0], row[1], priority, row[3], row[4], enabled, row[6], row[7], row[8], row[9], row[10], row[11]}
		value, err := json.Marshal(record)
		return []byte(record.RuleID), value, err
	})
}

func (store *masterStore) importGroups(source csvMasterSource) error {
	return store.importRows(groupsBucket, source.readGroups, func(row []string) ([]byte, []byte, error) {
		record := groupRecord{row[0], row[1], row[2], row[3], row[4], row[5], row[6]}
		value, err := json.Marshal(record)
		return []byte(record.GroupID), value, err
	})
}

func (store *masterStore) importMembers(source csvMasterSource) error {
	return store.importRows(membersBucket, source.readMembers, func(row []string) ([]byte, []byte, error) {
		value, err := json.Marshal(memberValue{CreatedAt: row[2], UpdatedAt: row[3]})
		return memberKey(row[0], row[1]), value, err
	})
}

func memberKey(groupID, address string) []byte {
	key := make([]byte, 0, len(groupID)+len(address)+1)
	key = append(key, groupID...)
	key = append(key, memberKeySeparator)
	return append(key, address...)
}

func splitMemberKey(key []byte) (string, string, error) {
	index := bytes.IndexByte(key, memberKeySeparator)
	if index < 1 || index == len(key)-1 {
		return "", "", errors.New("invalid member DB key")
	}
	return string(key[:index]), string(key[index+1:]), nil
}

func revisionBytes(value uint64) []byte {
	result := make([]byte, 8)
	binary.BigEndian.PutUint64(result, value)
	return result
}

func readRevision(bucket *bolt.Bucket) uint64 {
	value := bucket.Get(revisionKey)
	if len(value) != 8 {
		return 0
	}
	return binary.BigEndian.Uint64(value)
}

func (store *masterStore) dataVersion() (string, error) {
	var dataset string
	var revision uint64
	err := store.db.View(func(tx *bolt.Tx) error {
		metadata := tx.Bucket(metadataBucket)
		if metadata == nil || string(metadata.Get(initializedKey)) != "true" {
			return errors.New("master DB import is incomplete")
		}
		dataset = string(metadata.Get(datasetKey))
		revision = readRevision(metadata)
		return nil
	})
	if err != nil {
		return "", err
	}
	if revision == 0 {
		return dataset, nil
	}
	return fmt.Sprintf("%s+r%d", dataset, revision), nil
}

func readJSONBucket[T any](store *masterStore, bucketName []byte, toRow func(T) []string, consume func([]string) error) error {
	return store.db.View(func(tx *bolt.Tx) error {
		bucket := tx.Bucket(bucketName)
		if bucket == nil {
			return fmt.Errorf("missing DB bucket %q", bucketName)
		}
		return bucket.ForEach(func(_, value []byte) error {
			var record T
			if err := json.Unmarshal(value, &record); err != nil {
				return err
			}
			return consume(toRow(record))
		})
	})
}

func (store *masterStore) readPolicies(consume func([]string) error) error {
	return readJSONBucket(store, policiesBucket, func(value policyRecord) []string {
		return []string{value.PolicyID, value.TenantID, value.DepartmentID, value.DefaultAction, strconv.FormatBool(value.Enabled), value.CreatedAt, value.UpdatedAt}
	}, consume)
}

func (store *masterStore) readRules(consume func([]string) error) error {
	return readJSONBucket(store, rulesBucket, func(value ruleRecord) []string {
		return []string{value.RuleID, value.PolicyID, strconv.Itoa(value.Priority), value.RuleType, value.Action, strconv.FormatBool(value.Enabled), value.RegexPattern, value.SourceIPGroupKey, value.StartTimeUTC, value.EndTimeUTC, value.CreatedAt, value.UpdatedAt}
	}, consume)
}

func (store *masterStore) readGroups(consume func([]string) error) error {
	return readJSONBucket(store, groupsBucket, func(value groupRecord) []string {
		return []string{value.GroupID, value.TenantID, value.DepartmentID, value.GroupKey, value.Name, value.CreatedAt, value.UpdatedAt}
	}, consume)
}

func (store *masterStore) readMembers(consume func([]string) error) error {
	return store.db.View(func(tx *bolt.Tx) error {
		bucket := tx.Bucket(membersBucket)
		if bucket == nil {
			return errors.New("missing source IP group member DB bucket")
		}
		return bucket.ForEach(func(key, value []byte) error {
			groupID, address, err := splitMemberKey(key)
			if err != nil {
				return err
			}
			var stored memberValue
			if err := json.Unmarshal(value, &stored); err != nil {
				return err
			}
			return consume([]string{groupID, address, stored.CreatedAt, stored.UpdatedAt})
		})
	})
}

func LoadEngineFromMasterStore(store *masterStore) (*Engine, error) {
	return loadEngine(store)
}
