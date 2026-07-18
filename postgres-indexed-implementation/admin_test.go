package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testingMasterStore(t *testing.T) *masterStore {
	t.Helper()
	url := strings.TrimSpace(os.Getenv("MASTER_DATABASE_URL"))
	if url == "" {
		t.Skipf("MASTER_DATABASE_URL not set; skipping PostgreSQL-backed master test")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	store, err := openMasterStore(ctx, url)
	if err != nil {
		t.Fatalf("open master store: %v", err)
	}
	tables := []string{membersTable, groupsTable, rulesTable, policiesTable}
	for _, table := range tables {
		if _, err := store.pool.Exec(ctx, fmt.Sprintf("TRUNCATE TABLE %s", table)); err != nil {
			t.Fatalf("truncate %s: %v", table, err)
		}
	}
	for _, key := range []string{"initialized", "dataset_version"} {
		if _, err := store.pool.Exec(ctx, "DELETE FROM metadata WHERE key = $1", key); err != nil {
			t.Fatalf("clear metadata %s: %v", key, err)
		}
	}
	if _, err := store.pool.Exec(ctx, "INSERT INTO metadata(key, value) VALUES ('revision', '0') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"); err != nil {
		t.Fatalf("reset revision: %v", err)
	}
	return store
}

func adminRequest(t *testing.T, client *http.Client, method, url string, body any, wantStatus int) {
	t.Helper()
	var payload bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&payload).Encode(body); err != nil {
			t.Fatal(err)
		}
	}
	request, err := http.NewRequest(method, url, &payload)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != wantStatus {
		var message any
		_ = json.NewDecoder(response.Body).Decode(&message)
		t.Fatalf("%s %s: status=%d body=%v", method, url, response.StatusCode, message)
	}
}

func TestMasterDBImportCRUDAndPersistence(t *testing.T) {
	data := filepath.Join("..", "benchmark-data", "development-v1")
	store := testingMasterStore(t)
	defer store.Close()

	ctx := context.Background()
	if err := store.ensureImported(ctx, data); err != nil {
		t.Fatal(err)
	}
	engine, err := LoadEngineFromMasterStore(ctx, store)
	if err != nil {
		t.Fatal(err)
	}
	holder := newEngineHolder(engine)
	server := httptest.NewServer(newRuntimeHandler(holder, newMasterService(store, holder)))
	client := server.Client()

	policyURL := server.URL + "/v1/admin/policies/maintenance-policy"
	groupURL := server.URL + "/v1/admin/source-ip-groups/maintenance-group"
	memberURL := groupURL + "/members/203.0.113.7"
	ruleURL := server.URL + "/v1/admin/rules/maintenance-rule"
	adminRequest(t, client, http.MethodPut, policyURL, policyRecord{TenantID: "maintenance", DefaultAction: "ALLOW", Enabled: true}, http.StatusOK)
	adminRequest(t, client, http.MethodPut, groupURL, groupRecord{TenantID: "maintenance", GroupKey: "office", Name: "Office"}, http.StatusOK)
	adminRequest(t, client, http.MethodPut, memberURL, memberRecord{}, http.StatusOK)
	adminRequest(t, client, http.MethodPut, ruleURL, ruleRecord{PolicyID: "maintenance-policy", Priority: 1, RuleType: "SOURCE_IPV4_GROUP", Action: "DENY", Enabled: true, SourceIPGroupKey: "office"}, http.StatusOK)
	adminRequest(t, client, http.MethodGet, ruleURL, nil, http.StatusOK)
	adminRequest(t, client, http.MethodPut, ruleURL, ruleRecord{PolicyID: "maintenance-policy", Priority: 1, RuleType: "SOURCE_IPV4_GROUP", Action: "ALLOW", Enabled: true, SourceIPGroupKey: "office"}, http.StatusOK)
	adminRequest(t, client, http.MethodPut, ruleURL, ruleRecord{PolicyID: "maintenance-policy", Priority: 1, RuleType: "SOURCE_IPV4_GROUP", Action: "DENY", Enabled: true, SourceIPGroupKey: "office"}, http.StatusOK)

	result, err := holder.Load().Evaluate(AccessLog{AccessID: "maintenance-access", SourceIPv4: "203.0.113.7", URLPath: "/maintenance/department/resource", AccessTimestampUTC: "2026-07-18T12:00:00Z"})
	if err != nil {
		t.Fatal(err)
	}
	if result.MatchedRuleID != "maintenance-rule" || result.Action != "DENY" {
		t.Fatalf("updated snapshot was not used: %+v", result)
	}
	if holder.Load().DataVersion == engine.DataVersion {
		t.Fatal("data_version did not change after maintenance")
	}

	adminRequest(t, client, http.MethodPut, server.URL+"/v1/admin/rules/invalid-rule", ruleRecord{PolicyID: "missing", Priority: 1, RuleType: "SOURCE_IPV4_GROUP", Action: "DENY", Enabled: true, SourceIPGroupKey: "office"}, http.StatusConflict)
	adminRequest(t, client, http.MethodGet, server.URL+"/v1/admin/rules/invalid-rule", nil, http.StatusNotFound)

	server.Close()

	// Verify persistence across a fresh store handle (the same PostgreSQL database).
	store.Close()
	store2, err := openMasterStore(ctx, os.Getenv("MASTER_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer store2.Close()
	if err := store2.ensureImported(ctx, data); err != nil {
		t.Fatal(err)
	}
	reloaded, err := LoadEngineFromMasterStore(ctx, store2)
	if err != nil {
		t.Fatal(err)
	}
	result, err = reloaded.Evaluate(AccessLog{AccessID: "maintenance-access", SourceIPv4: "203.0.113.7", URLPath: "/maintenance/department/resource", AccessTimestampUTC: "2026-07-18T12:00:00Z"})
	if err != nil || result.MatchedRuleID != "maintenance-rule" {
		t.Fatalf("maintained data was not persisted: result=%+v err=%v", result, err)
	}

	reloadedHolder := newEngineHolder(reloaded)
	reloadedServer := httptest.NewServer(newRuntimeHandler(reloadedHolder, newMasterService(store2, reloadedHolder)))
	defer reloadedServer.Close()
	reloadedClient := reloadedServer.Client()
	adminRequest(t, reloadedClient, http.MethodDelete, reloadedServer.URL+"/v1/admin/source-ip-groups/maintenance-group/members/203.0.113.7", nil, http.StatusOK)
	adminRequest(t, reloadedClient, http.MethodDelete, reloadedServer.URL+"/v1/admin/rules/maintenance-rule", nil, http.StatusOK)
	adminRequest(t, reloadedClient, http.MethodDelete, reloadedServer.URL+"/v1/admin/source-ip-groups/maintenance-group", nil, http.StatusOK)
	adminRequest(t, reloadedClient, http.MethodDelete, reloadedServer.URL+"/v1/admin/policies/maintenance-policy", nil, http.StatusOK)
	adminRequest(t, reloadedClient, http.MethodGet, reloadedServer.URL+"/v1/admin/policies/maintenance-policy", nil, http.StatusNotFound)
}
