package main

import (
	"encoding/csv"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	resultHeader = "access_id,selected_policy_id,matched_rule_id,action"
)

type Policy struct {
	ID            string
	TenantID      string
	DepartmentID  string
	DefaultAction string
	Rules         []*Rule
}

type Rule struct {
	ID       string
	Priority int
	Type     string
	Action   string
	Pattern  string
	GroupKey string
	Start    int
	End      int
	Regex    regexpMatcher
}

// regexpMatcher keeps the engine testable without exposing a particular regexp implementation.
type regexpMatcher interface {
	MatchString(string) bool
}

type group struct {
	ID         string
	TenantID   string
	Department string
	Key        string
	MemberSet  map[string]struct{}
}

type Engine struct {
	tenantPolicies map[string]*Policy
	deptPolicies   map[string]*Policy
	groupsByScope  map[string]map[string]*group
	DataVersion    string
}

type AccessLog struct {
	AccessID           string `json:"access_id" parquet:"access_id"`
	SourceIPv4         string `json:"source_ipv4" parquet:"source_ipv4"`
	URLPath            string `json:"url_path" parquet:"url_path"`
	AccessTimestampUTC string `json:"access_timestamp_utc" parquet:"access_timestamp_utc"`
	Referer            string `json:"referer" parquet:"referer"`
	UserAgent          string `json:"user_agent" parquet:"user_agent"`
}

type Result struct {
	AccessID         string
	SelectedPolicyID string
	MatchedRuleID    string
	Action           string
}

func main() {
	if len(os.Args) < 2 {
		usage(os.Stderr)
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "batch":
		err = batchCommand(os.Args[2:])
	case "validate":
		err = validateCommand(os.Args[2:])
	case "serve":
		err = serveCommand(os.Args[2:])
	default:
		usage(os.Stderr)
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, "usage: firewall-reference <batch|validate|serve> [flags]")
}

func batchCommand(arguments []string) error {
	flags := flag.NewFlagSet("batch", flag.ContinueOnError)
	dataDir := flags.String("data", "", "dataset directory")
	input := flags.String("input", "", "access-log Parquet or CSV file")
	output := flags.String("output", "", "results.csv destination")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if *dataDir == "" || *input == "" || *output == "" {
		return errors.New("--data, --input and --output are required")
	}
	engine, err := LoadEngine(*dataDir)
	if err != nil {
		return err
	}
	accesses, err := ReadAccessLogs(*input)
	if err != nil {
		return err
	}
	results := make([]Result, 0, len(accesses))
	for _, access := range accesses {
		result, err := engine.Evaluate(access)
		if err != nil {
			return fmt.Errorf("evaluate %s: %w", access.AccessID, err)
		}
		results = append(results, result)
	}
	return WriteResults(*output, results)
}

func validateCommand(arguments []string) error {
	flags := flag.NewFlagSet("validate", flag.ContinueOnError)
	dataDir := flags.String("data", "", "dataset directory")
	input := flags.String("input", "", "access-log Parquet or CSV file")
	expectedDir := flags.String("expected", "", "expected_results directory")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if *dataDir == "" || *input == "" || *expectedDir == "" {
		return errors.New("--data, --input and --expected are required")
	}
	engine, err := LoadEngine(*dataDir)
	if err != nil {
		return err
	}
	accesses, err := ReadAccessLogs(*input)
	if err != nil {
		return err
	}
	actual := make(map[string]Result, len(accesses))
	for _, access := range accesses {
		result, err := engine.Evaluate(access)
		if err != nil {
			return err
		}
		if _, exists := actual[result.AccessID]; exists {
			return fmt.Errorf("duplicate input access_id %q", result.AccessID)
		}
		actual[result.AccessID] = result
	}
	expected, err := ReadExpected(*expectedDir)
	if err != nil {
		return err
	}
	if len(actual) != len(expected) {
		return fmt.Errorf("result count mismatch: evaluated=%d expected=%d", len(actual), len(expected))
	}
	for accessID, want := range expected {
		got, exists := actual[accessID]
		if !exists || got != want {
			return fmt.Errorf("result mismatch for %s: got=%+v want=%+v", accessID, got, want)
		}
	}
	fmt.Printf("validated %d results\n", len(expected))
	return nil
}

func serveCommand(arguments []string) error {
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	dataDir := flags.String("data", "", "dataset directory")
	listen := flags.String("listen", ":8080", "listen address")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if *dataDir == "" {
		return errors.New("--data is required")
	}
	engine, err := LoadEngine(*dataDir)
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/ready", func(writer http.ResponseWriter, request *http.Request) {
		writeJSON(writer, http.StatusOK, map[string]string{"status": "READY", "data_version": engine.DataVersion})
	})
	mux.HandleFunc("POST /v1/firewall/evaluate", func(writer http.ResponseWriter, request *http.Request) {
		defer request.Body.Close()
		decoder := json.NewDecoder(http.MaxBytesReader(writer, request.Body, 1<<20))
		decoder.DisallowUnknownFields()
		var access AccessLog
		if err := decoder.Decode(&access); err != nil {
			writeJSON(writer, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		result, err := engine.Evaluate(access)
		if err != nil {
			writeJSON(writer, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(writer, http.StatusOK, responseResult(result))
	})
	server := &http.Server{Addr: *listen, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	return server.ListenAndServe()
}

func responseResult(result Result) map[string]any {
	var matchedRule any
	if result.MatchedRuleID != "" {
		matchedRule = result.MatchedRuleID
	}
	return map[string]any{
		"access_id": result.AccessID, "selected_policy_id": result.SelectedPolicyID,
		"matched_rule_id": matchedRule, "action": result.Action,
	}
}

func writeJSON(writer http.ResponseWriter, status int, body any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(body)
}

func scopeKey(tenantID, departmentID string) string { return tenantID + "\x00" + departmentID }

func actionValid(value string) bool { return value == "ALLOW" || value == "DENY" }

func csvFiles(directory string) ([]string, error) {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return nil, err
	}
	files := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".csv") {
			files = append(files, filepath.Join(directory, entry.Name()))
		}
	}
	sort.Strings(files)
	if len(files) == 0 {
		return nil, fmt.Errorf("no CSV files in %s", directory)
	}
	return files, nil
}

func readCSVDirectory(directory string, expectedHeader []string, consume func(map[string]string) error) error {
	files, err := csvFiles(directory)
	if err != nil {
		return err
	}
	for _, path := range files {
		stream, err := os.Open(path)
		if err != nil {
			return err
		}
		reader := csv.NewReader(stream)
		reader.FieldsPerRecord = len(expectedHeader)
		header, err := reader.Read()
		if err == nil && !slicesEqual(header, expectedHeader) {
			err = fmt.Errorf("%s: invalid CSV header %v", path, header)
		}
		for err == nil {
			values, readErr := reader.Read()
			if errors.Is(readErr, io.EOF) {
				break
			}
			if readErr != nil {
				err = fmt.Errorf("%s: %w", path, readErr)
				break
			}
			row := make(map[string]string, len(expectedHeader))
			for index, key := range expectedHeader {
				row[key] = values[index]
			}
			err = consume(row)
		}
		closeErr := stream.Close()
		if err != nil {
			return err
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func slicesEqual(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}
