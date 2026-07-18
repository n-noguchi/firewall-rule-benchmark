package main

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type benchmarkReport struct {
	Mode                string                  `json:"mode"`
	StartedAt           string                  `json:"started_at"`
	CompletedAt         string                  `json:"completed_at"`
	WarmupRuns          int                     `json:"warmup_runs"`
	MeasuredRuns        int                     `json:"measured_runs"`
	AccessCount         int                     `json:"access_count"`
	ValidationPerformed bool                    `json:"validation_performed"`
	AllCorrect          bool                    `json:"all_correct"`
	Batch               []batchMeasurement      `json:"batch,omitempty"`
	Sequential          []sequentialMeasurement `json:"sequential,omitempty"`
}

type batchMeasurement struct {
	Run       int   `json:"run"`
	ElapsedNS int64 `json:"elapsed_ns"`
	Validated bool  `json:"validated"`
	Correct   bool  `json:"correct"`
}

type sequentialMeasurement struct {
	Run       int   `json:"run"`
	TotalNS   int64 `json:"total_ns"`
	P50NS     int64 `json:"p50_ns"`
	P99NS     int64 `json:"p99_ns"`
	MaximumNS int64 `json:"maximum_ns"`
	Correct   bool  `json:"correct"`
}

func benchmarkCommand(arguments []string) error {
	flags := flag.NewFlagSet("benchmark", flag.ContinueOnError)
	mode := flags.String("mode", "", "batch or sequential")
	input := flags.String("input", "", "access-log Parquet/CSV file or directory")
	expectedDirectory := flags.String("expected", "", "expected_results directory")
	reportPath := flags.String("report", "", "optional JSON report destination")
	warmup := flags.Int("warmup", 3, "full warm-up runs")
	runs := flags.Int("runs", 10, "measured runs")
	cooldown := flags.Duration("cooldown", 200*time.Millisecond, "pause between full runs (outside measurements)")
	server := flags.String("server", "http://127.0.0.1:8080", "sequential target URL")
	batchExecutable := flags.String("batch-executable", "/benchmark/run-batch", "batch executable or script")
	outputDirectory := flags.String("output-directory", "", "batch result/report directory")
	workers := flags.Int("workers", 0, "batch evaluation workers (0 = GOMAXPROCS)")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if (*mode != "batch" && *mode != "sequential") || *input == "" || *warmup < 0 || *runs < 1 || *cooldown < 0 {
		return errors.New("--mode batch|sequential, --input, --runs >= 1, --warmup >= 0 and nonnegative --cooldown are required")
	}
	if *mode == "sequential" && *expectedDirectory == "" {
		return errors.New("--expected is required in sequential mode")
	}
	accesses, err := ReadAccessLogs(*input)
	if err != nil {
		return err
	}
	var expected map[string]Result
	if *expectedDirectory != "" {
		expected, err = ReadExpected(*expectedDirectory)
		if err != nil {
			return err
		}
		if err := verifyAccessSet(accesses, expected); err != nil {
			return err
		}
	}
	started := time.Now()
	report := benchmarkReport{
		Mode: *mode, StartedAt: started.Format(time.RFC3339Nano), WarmupRuns: *warmup,
		MeasuredRuns: *runs, AccessCount: len(accesses), ValidationPerformed: expected != nil, AllCorrect: expected != nil,
	}
	switch *mode {
	case "batch":
		if *outputDirectory == "" {
			return errors.New("--output-directory is required in batch mode")
		}
		measurements, err := measureBatch(*batchExecutable, *input, *outputDirectory, expected, *workers, *warmup, *runs, *cooldown)
		if err != nil {
			return err
		}
		report.Batch = measurements
	case "sequential":
		measurements, err := measureSequential(*server, accesses, expected, *warmup, *runs, *cooldown)
		if err != nil {
			return err
		}
		report.Sequential = measurements
	}
	report.CompletedAt = time.Now().Format(time.RFC3339Nano)
	encoded, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	encoded = append(encoded, '\n')
	if *reportPath != "" {
		if err := os.MkdirAll(filepath.Dir(*reportPath), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(*reportPath, encoded, 0o644); err != nil {
			return err
		}
	}
	_, err = os.Stdout.Write(encoded)
	return err
}

func verifyAccessSet(accesses []AccessLog, expected map[string]Result) error {
	if len(accesses) != len(expected) {
		return fmt.Errorf("input/expected count mismatch: input=%d expected=%d", len(accesses), len(expected))
	}
	for _, access := range accesses {
		if _, exists := expected[access.AccessID]; !exists {
			return fmt.Errorf("missing expected result for %q", access.AccessID)
		}
	}
	return nil
}

func measureBatch(executable, input, outputDirectory string, expected map[string]Result, workers, warmup, runs int, cooldown time.Duration) ([]batchMeasurement, error) {
	if err := os.MkdirAll(outputDirectory, 0o755); err != nil {
		return nil, err
	}
	totalRuns := warmup + runs
	measurements := make([]batchMeasurement, 0, runs)
	for iteration := 0; iteration < totalRuns; iteration++ {
		output := filepath.Join(outputDirectory, fmt.Sprintf("batch-run-%03d.csv", iteration+1))
		arguments := []string{"--input", input, "--output", output}
		if workers != 0 {
			arguments = append(arguments, "--workers", fmt.Sprint(workers))
		}
		command := exec.Command(executable, arguments...)
		start := time.Now()
		err := command.Run()
		elapsed := time.Since(start)
		if err != nil {
			return nil, fmt.Errorf("batch iteration %d failed: %w", iteration+1, err)
		}
		actual, err := readResultFile(output)
		if err != nil {
			return nil, fmt.Errorf("batch iteration %d output: %w", iteration+1, err)
		}
		validated := expected != nil
		if validated {
			if err := verifyResultSet(actual, expected); err != nil {
				return nil, fmt.Errorf("batch iteration %d correctness: %w", iteration+1, err)
			}
		}
		if iteration >= warmup {
			measurements = append(measurements, batchMeasurement{Run: iteration - warmup + 1, ElapsedNS: elapsed.Nanoseconds(), Validated: validated, Correct: validated})
		}
		if iteration+1 < totalRuns && cooldown != 0 {
			time.Sleep(cooldown)
		}
	}
	return measurements, nil
}

type resultValidationReport struct {
	Files      int    `json:"files"`
	Rows       int    `json:"rows"`
	AllCorrect bool   `json:"all_correct"`
	Completed  string `json:"completed_at"`
}

func verifyResultsCommand(arguments []string) error {
	flags := flag.NewFlagSet("verify-results", flag.ContinueOnError)
	expectedDirectory := flags.String("expected", "", "expected_results directory")
	resultDirectory := flags.String("results", "", "directory containing batch-run-*.csv")
	reportPath := flags.String("report", "", "optional JSON report destination")
	if err := flags.Parse(arguments); err != nil {
		return err
	}
	if *expectedDirectory == "" || *resultDirectory == "" {
		return errors.New("--expected and --results are required")
	}
	expected, err := ReadExpected(*expectedDirectory)
	if err != nil {
		return err
	}
	files, err := filepath.Glob(filepath.Join(*resultDirectory, "batch-run-*.csv"))
	if err != nil {
		return err
	}
	sort.Strings(files)
	if len(files) == 0 {
		return fmt.Errorf("no batch-run-*.csv files in %s", *resultDirectory)
	}
	for _, path := range files {
		actual, err := readResultFile(path)
		if err != nil {
			return fmt.Errorf("%s: %w", path, err)
		}
		if err := verifyResultSet(actual, expected); err != nil {
			return fmt.Errorf("%s: %w", path, err)
		}
	}
	report := resultValidationReport{Files: len(files), Rows: len(files) * len(expected), AllCorrect: true, Completed: time.Now().Format(time.RFC3339Nano)}
	encoded, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	encoded = append(encoded, '\n')
	if *reportPath != "" {
		if err := os.MkdirAll(filepath.Dir(*reportPath), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(*reportPath, encoded, 0o644); err != nil {
			return err
		}
	}
	_, err = os.Stdout.Write(encoded)
	return err
}

func measureSequential(server string, accesses []AccessLog, expected map[string]Result, warmup, runs int, cooldown time.Duration) ([]sequentialMeasurement, error) {
	payloads := make([][]byte, len(accesses))
	for index, access := range accesses {
		encoded, err := json.Marshal(access)
		if err != nil {
			return nil, err
		}
		payloads[index] = encoded
	}
	transport := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         (&net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		ForceAttemptHTTP2:   false,
		MaxIdleConns:        1,
		MaxIdleConnsPerHost: 1,
		MaxConnsPerHost:     1,
		IdleConnTimeout:     90 * time.Second,
		DisableCompression:  true,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second}
	ready, err := client.Get(strings.TrimRight(server, "/") + "/health/ready")
	if err != nil {
		return nil, fmt.Errorf("preconnect: %w", err)
	}
	_, readErr := io.Copy(io.Discard, ready.Body)
	closeErr := ready.Body.Close()
	if ready.StatusCode != http.StatusOK || readErr != nil || closeErr != nil {
		return nil, fmt.Errorf("preconnect READY failed: status=%s read=%v close=%v", ready.Status, readErr, closeErr)
	}
	endpoint := strings.TrimRight(server, "/") + "/v1/firewall/evaluate"
	totalRuns := warmup + runs
	measurements := make([]sequentialMeasurement, 0, runs)
	for iteration := 0; iteration < totalRuns; iteration++ {
		latencies := make([]int64, len(accesses))
		var total int64
		for index, access := range accesses {
			request, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(payloads[index]))
			if err != nil {
				return nil, err
			}
			request.Header.Set("Content-Type", "application/json")
			start := time.Now()
			response, err := client.Do(request)
			if err != nil {
				return nil, fmt.Errorf("sequential iteration %d access %s: %w", iteration+1, access.AccessID, err)
			}
			body, readErr := io.ReadAll(response.Body)
			elapsed := time.Since(start)
			closeErr := response.Body.Close()
			if readErr != nil || closeErr != nil || response.StatusCode != http.StatusOK {
				return nil, fmt.Errorf("sequential iteration %d access %s: status=%s read=%v close=%v", iteration+1, access.AccessID, response.Status, readErr, closeErr)
			}
			if err := verifySequentialResponse(body, access.AccessID, expected[access.AccessID]); err != nil {
				return nil, fmt.Errorf("sequential iteration %d: %w", iteration+1, err)
			}
			latencies[index] = elapsed.Nanoseconds()
			total += latencies[index]
		}
		if iteration >= warmup {
			sorted := append([]int64(nil), latencies...)
			sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
			measurements = append(measurements, sequentialMeasurement{
				Run: iteration - warmup + 1, TotalNS: total, P50NS: nearestRank(sorted, 0.50),
				P99NS: nearestRank(sorted, 0.99), MaximumNS: sorted[len(sorted)-1], Correct: true,
			})
		}
		if iteration+1 < totalRuns && cooldown != 0 {
			time.Sleep(cooldown)
		}
	}
	return measurements, nil
}

func nearestRank(sorted []int64, percentile float64) int64 {
	position := int(float64(len(sorted))*percentile+0.999999999) - 1
	if position < 0 {
		position = 0
	}
	if position >= len(sorted) {
		position = len(sorted) - 1
	}
	return sorted[position]
}

func verifySequentialResponse(body []byte, accessID string, expected Result) error {
	var response evaluationResponse
	if err := json.Unmarshal(body, &response); err != nil {
		return fmt.Errorf("access %s invalid JSON: %w", accessID, err)
	}
	matched := ""
	if response.MatchedRuleID != nil {
		matched = *response.MatchedRuleID
	}
	if response.AccessID != expected.AccessID || response.SelectedPolicyID != expected.SelectedPolicyID || matched != expected.MatchedRuleID || response.Action != expected.Action {
		return fmt.Errorf("access %s mismatch: got=%+v want=%+v", accessID, response, expected)
	}
	return nil
}

func readResultFile(path string) (map[string]Result, error) {
	stream, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer stream.Close()
	reader := csv.NewReader(stream)
	reader.FieldsPerRecord = 4
	header, err := reader.Read()
	if err != nil {
		return nil, err
	}
	if strings.Join(header, ",") != resultHeader {
		return nil, fmt.Errorf("invalid result header %v", header)
	}
	results := make(map[string]Result)
	for {
		row, err := reader.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, err
		}
		if row[0] == "" || row[1] == "" || !actionValid(row[3]) {
			return nil, fmt.Errorf("malformed result row %v", row)
		}
		if _, exists := results[row[0]]; exists {
			return nil, fmt.Errorf("duplicate result access_id %q", row[0])
		}
		results[row[0]] = Result{AccessID: row[0], SelectedPolicyID: row[1], MatchedRuleID: row[2], Action: row[3]}
	}
	return results, nil
}

func verifyResultSet(actual, expected map[string]Result) error {
	if len(actual) != len(expected) {
		return fmt.Errorf("result count mismatch: actual=%d expected=%d", len(actual), len(expected))
	}
	for accessID, want := range expected {
		if got, exists := actual[accessID]; !exists || got != want {
			return fmt.Errorf("access %s mismatch: got=%+v want=%+v", accessID, got, want)
		}
	}
	return nil
}
