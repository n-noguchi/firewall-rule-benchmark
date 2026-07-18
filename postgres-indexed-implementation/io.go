package main

import (
	"encoding/csv"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/parquet-go/parquet-go"
)

var accessHeader = []string{"access_id", "source_ipv4", "url_path", "access_timestamp_utc", "referer", "user_agent"}

func ReadAccessLogs(path string) ([]AccessLog, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		logs, err := readAccessLogFile(path)
		if err != nil {
			return nil, err
		}
		return logs, validateAccessIDs(logs)
	}
	entries, err := os.ReadDir(path)
	if err != nil {
		return nil, err
	}
	files := make([]string, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		extension := strings.ToLower(filepath.Ext(entry.Name()))
		if extension == ".parquet" || extension == ".csv" {
			files = append(files, filepath.Join(path, entry.Name()))
		}
	}
	sort.Strings(files)
	if len(files) == 0 {
		return nil, fmt.Errorf("no access-log files in %s", path)
	}
	logs := make([]AccessLog, 0)
	for _, file := range files {
		part, err := readAccessLogFile(file)
		if err != nil {
			return nil, err
		}
		logs = append(logs, part...)
	}
	return logs, validateAccessIDs(logs)
}

func readAccessLogFile(path string) ([]AccessLog, error) {
	if strings.EqualFold(filepath.Ext(path), ".parquet") {
		logs, err := parquet.ReadFile[AccessLog](path)
		if err != nil {
			return nil, fmt.Errorf("read Parquet %s: %w", path, err)
		}
		return logs, nil
	}
	stream, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer stream.Close()
	reader := csv.NewReader(stream)
	reader.FieldsPerRecord = len(accessHeader)
	header, err := reader.Read()
	if err != nil {
		return nil, err
	}
	if !equalStrings(header, accessHeader) {
		return nil, fmt.Errorf("invalid access CSV header %v", header)
	}
	logs := make([]AccessLog, 0)
	for {
		row, err := reader.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, err
		}
		logs = append(logs, AccessLog{AccessID: row[0], SourceIPv4: row[1], URLPath: row[2], AccessTimestampUTC: row[3], Referer: row[4], UserAgent: row[5]})
	}
	return logs, nil
}

func validateAccessIDs(logs []AccessLog) error {
	seen := make(map[string]struct{}, len(logs))
	for _, log := range logs {
		if log.AccessID == "" {
			return errors.New("empty access_id")
		}
		if _, exists := seen[log.AccessID]; exists {
			return fmt.Errorf("duplicate access_id %q", log.AccessID)
		}
		seen[log.AccessID] = struct{}{}
	}
	return nil
}

func WriteResults(path string, results []Result) error {
	if parent := filepath.Dir(path); parent != "." {
		if err := os.MkdirAll(parent, 0o755); err != nil {
			return err
		}
	}
	stream, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	writer := csv.NewWriter(stream)
	writer.UseCRLF = false
	if err := writer.Write(strings.Split(resultHeader, ",")); err != nil {
		stream.Close()
		return err
	}
	for _, result := range results {
		if err := writer.Write([]string{result.AccessID, result.SelectedPolicyID, result.MatchedRuleID, result.Action}); err != nil {
			stream.Close()
			return err
		}
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		stream.Close()
		return err
	}
	return stream.Close()
}

func ReadExpected(directory string) (map[string]Result, error) {
	files, err := csvFiles(directory)
	if err != nil {
		return nil, err
	}
	results := make(map[string]Result)
	for _, path := range files {
		stream, err := os.Open(path)
		if err != nil {
			return nil, err
		}
		reader := csv.NewReader(stream)
		reader.FieldsPerRecord = 4
		header, readErr := reader.Read()
		if readErr == nil && strings.Join(header, ",") != resultHeader {
			readErr = fmt.Errorf("invalid expected-result header")
		}
		for readErr == nil {
			row, rowErr := reader.Read()
			if errors.Is(rowErr, io.EOF) {
				break
			}
			if rowErr != nil {
				readErr = rowErr
				break
			}
			if row[0] == "" || row[1] == "" || !actionValid(row[3]) {
				readErr = errors.New("malformed expected result")
				break
			}
			if _, exists := results[row[0]]; exists {
				readErr = fmt.Errorf("duplicate expected access_id %q", row[0])
				break
			}
			results[row[0]] = Result{AccessID: row[0], SelectedPolicyID: row[1], MatchedRuleID: row[2], Action: row[3]}
		}
		closeErr := stream.Close()
		if readErr != nil {
			return nil, fmt.Errorf("%s: %w", path, readErr)
		}
		if closeErr != nil {
			return nil, closeErr
		}
	}
	return results, nil
}
