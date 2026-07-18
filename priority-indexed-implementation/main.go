package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		usage(os.Stderr)
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "batch":
		err = batchCommand(os.Args[2:])
	case "batch-client":
		err = batchClientCommand(os.Args[2:])
	case "validate":
		err = validateCommand(os.Args[2:])
	case "serve":
		err = serveCommand(os.Args[2:])
	case "health":
		err = healthCommand(os.Args[2:])
	case "benchmark":
		err = benchmarkCommand(os.Args[2:])
	case "verify-results":
		err = verifyResultsCommand(os.Args[2:])
	default:
		usage(os.Stderr)
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func usage(writer io.Writer) {
	fmt.Fprintln(writer, "usage: firewall-priority-indexed <batch|batch-client|validate|serve|health|benchmark|verify-results> [flags]")
}

func batchCommand(arguments []string) error {
	flags := flag.NewFlagSet("batch", flag.ContinueOnError)
	dataDirectory := flags.String("data", "", "dataset directory")
	input := flags.String("input", "", "access-log Parquet/CSV file or directory")
	output := flags.String("output", "", "results.csv destination")
	workers := flags.Int("workers", 0, "evaluation workers (0 = GOMAXPROCS)")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if *dataDirectory == "" || *input == "" || *output == "" {
		return errors.New("--data, --input and --output are required")
	}
	engine, err := LoadEngine(*dataDirectory)
	if err != nil {
		return err
	}
	return runBatch(engine, *input, *output, *workers)
}

func runBatch(engine *Engine, input, output string, workers int) error {
	accesses, err := ReadAccessLogs(input)
	if err != nil {
		return err
	}
	results, err := engine.EvaluateMany(accesses, workers)
	if err != nil {
		return err
	}
	return WriteResults(output, results)
}

func validateCommand(arguments []string) error {
	flags := flag.NewFlagSet("validate", flag.ContinueOnError)
	dataDirectory := flags.String("data", "", "dataset directory")
	input := flags.String("input", "", "access-log Parquet/CSV file or directory")
	expectedDirectory := flags.String("expected", "", "expected_results directory")
	workers := flags.Int("workers", 0, "evaluation workers (0 = GOMAXPROCS)")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if *dataDirectory == "" || *input == "" || *expectedDirectory == "" {
		return errors.New("--data, --input and --expected are required")
	}
	engine, err := LoadEngine(*dataDirectory)
	if err != nil {
		return err
	}
	accesses, err := ReadAccessLogs(*input)
	if err != nil {
		return err
	}
	values, err := engine.EvaluateMany(accesses, *workers)
	if err != nil {
		return err
	}
	actual := make(map[string]Result, len(values))
	for _, value := range values {
		if _, exists := actual[value.AccessID]; exists {
			return fmt.Errorf("duplicate result access_id %q", value.AccessID)
		}
		actual[value.AccessID] = value
	}
	expected, err := ReadExpected(*expectedDirectory)
	if err != nil {
		return err
	}
	if len(actual) != len(expected) {
		return fmt.Errorf("result count mismatch: evaluated=%d expected=%d", len(actual), len(expected))
	}
	for accessID, want := range expected {
		if got, exists := actual[accessID]; !exists || got != want {
			return fmt.Errorf("result mismatch for %s: got=%+v want=%+v", accessID, got, want)
		}
	}
	fmt.Printf("validated %d results\n", len(expected))
	return nil
}

type readyResponse struct {
	Status      string `json:"status"`
	DataVersion string `json:"data_version"`
}

type evaluationResponse struct {
	AccessID         string  `json:"access_id"`
	SelectedPolicyID string  `json:"selected_policy_id"`
	MatchedRuleID    *string `json:"matched_rule_id"`
	Action           string  `json:"action"`
}

type batchRequest struct {
	Input   string `json:"input"`
	Output  string `json:"output"`
	Workers int    `json:"workers,omitempty"`
}

func serveCommand(arguments []string) error {
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	dataDirectory := flags.String("data", "", "dataset directory")
	masterDB := flags.String("master-db", "", "persistent bbolt master DB path")
	listen := flags.String("listen", ":8080", "listen address")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if *dataDirectory == "" || *masterDB == "" {
		return errors.New("--data and --master-db are required")
	}
	store, err := openMasterStore(*masterDB)
	if err != nil {
		return err
	}
	defer store.Close()
	if err := store.ensureImported(*dataDirectory); err != nil {
		return err
	}
	engine, err := LoadEngineFromMasterStore(store)
	if err != nil {
		return err
	}
	holder := newEngineHolder(engine)
	mux := newRuntimeHandler(holder, newMasterService(store, holder))
	server := &http.Server{Addr: *listen, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	return server.ListenAndServe()
}

func newHandler(engine *Engine) http.Handler {
	return newRuntimeHandler(newEngineHolder(engine), nil)
}

func newRuntimeHandler(holder *engineHolder, master *masterService) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/ready", func(writer http.ResponseWriter, _ *http.Request) {
		engine := holder.Load()
		writeJSON(writer, http.StatusOK, readyResponse{Status: "READY", DataVersion: engine.DataVersion})
	})
	mux.HandleFunc("POST /v1/firewall/evaluate", func(writer http.ResponseWriter, request *http.Request) {
		defer request.Body.Close()
		var access AccessLog
		if err := decodeOneJSON(http.MaxBytesReader(writer, request.Body, 1<<20), &access); err != nil {
			writeJSON(writer, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		result, err := holder.Load().Evaluate(access)
		if err != nil {
			writeJSON(writer, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		response := evaluationResponse{AccessID: result.AccessID, SelectedPolicyID: result.SelectedPolicyID, Action: result.Action}
		if result.MatchedRuleID != "" {
			response.MatchedRuleID = &result.MatchedRuleID
		}
		writeJSON(writer, http.StatusOK, response)
	})
	mux.HandleFunc("POST /internal/run-batch", func(writer http.ResponseWriter, request *http.Request) {
		defer request.Body.Close()
		var value batchRequest
		if err := decodeOneJSON(http.MaxBytesReader(writer, request.Body, 1<<20), &value); err != nil || value.Input == "" || value.Output == "" {
			if err == nil {
				err = errors.New("input and output are required")
			}
			writeJSON(writer, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		if err := runBatch(holder.Load(), value.Input, value.Output, value.Workers); err != nil {
			writeJSON(writer, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(writer, http.StatusOK, map[string]string{"status": "complete"})
	})
	if master != nil {
		registerAdminHandlers(mux, master)
	}
	return mux
}

func decodeOneJSON(reader io.Reader, target any) error {
	decoder := json.NewDecoder(reader)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("multiple JSON values are not allowed")
		}
		return err
	}
	return nil
}

func writeJSON(writer http.ResponseWriter, status int, body any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(body)
}

func batchClientCommand(arguments []string) error {
	flags := flag.NewFlagSet("batch-client", flag.ContinueOnError)
	server := flags.String("server", "http://127.0.0.1:8080", "preloaded server URL")
	input := flags.String("input", "", "server-visible input path")
	output := flags.String("output", "", "server-visible output path")
	workers := flags.Int("workers", 0, "evaluation workers (0 = GOMAXPROCS)")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if *input == "" || *output == "" {
		return errors.New("--input and --output are required")
	}
	payload, err := json.Marshal(batchRequest{Input: *input, Output: *output, Workers: *workers})
	if err != nil {
		return err
	}
	response, err := http.Post(strings.TrimRight(*server, "/")+"/internal/run-batch", "application/json", bytes.NewReader(payload))
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
		return fmt.Errorf("batch server returned %s: %s", response.Status, strings.TrimSpace(string(body)))
	}
	return nil
}

func healthCommand(arguments []string) error {
	flags := flag.NewFlagSet("health", flag.ContinueOnError)
	server := flags.String("server", "http://127.0.0.1:8080", "server URL")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	client := &http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(strings.TrimRight(*server, "/") + "/health/ready")
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("not ready: %s", response.Status)
	}
	return nil
}
