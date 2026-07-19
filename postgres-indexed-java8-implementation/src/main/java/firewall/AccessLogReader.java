package firewall;

import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Reads access logs from a Parquet or CSV file (or directory of files). */
final class AccessLogReader {
    private static final String[] HEADER = {
            "access_id", "source_ipv4", "url_path", "access_timestamp_utc", "referer", "user_agent"
    };

    private AccessLogReader() {
    }

    static List<AccessLog> read(Path input) throws IOException {
        java.io.File file = input.toFile();
        if (!file.isDirectory()) {
            List<AccessLog> logs = new ArrayList<>();
            readFile(input, logs);
            validateAccessIds(logs);
            return logs;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(input)) {
            List<Path> collected = new ArrayList<>();
            stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".parquet") || name.endsWith(".csv");
            }).forEach(collected::add);
            collected.sort(null);
            files = collected;
        }
        if (files.isEmpty()) {
            throw new IOException("no access-log files in " + input);
        }
        List<AccessLog> logs = new ArrayList<>();
        for (Path path : files) {
            readFile(path, logs);
        }
        validateAccessIds(logs);
        return logs;
    }

    private static void readFile(Path path, List<AccessLog> sink) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".parquet")) {
            readParquet(path, sink);
        } else {
            readCsv(path, sink);
        }
    }

    private static void readCsv(Path path, List<AccessLog> sink) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("empty CSV file: " + path);
            }
            String[] header = CsvMasterSource.parseCsvLine(headerLine);
            if (!Arrays.equals(header, HEADER)) {
                throw new IOException("invalid access CSV header in " + path + ": " + Arrays.toString(header));
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] row = CsvMasterSource.parseCsvLine(line);
                if (row.length != HEADER.length) {
                    throw new IOException("malformed access row in " + path);
                }
                sink.add(new AccessLog(row[0], row[1], row[2], row[3], row[4], row[5]));
            }
        }
    }

    private static void readParquet(Path path, List<AccessLog> sink) throws IOException {
        Configuration configuration = new Configuration();
        // Force local file system so Hadoop does not try to talk to HDFS.
        configuration.set("fs.file.impl", "org.apache.hadoop.fs.LocalFileSystem");
        configuration.set("fs.defaultFS", "file:///");
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new org.apache.hadoop.fs.Path(path.toUri().toString()))
                .withConf(configuration)
                .build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                sink.add(new AccessLog(
                        stringField(record, "access_id"),
                        stringField(record, "source_ipv4"),
                        stringField(record, "url_path"),
                        stringField(record, "access_timestamp_utc"),
                        stringField(record, "referer"),
                        stringField(record, "user_agent")
                ));
            }
        }
    }

    private static String stringField(GenericRecord record, String name) {
        Object value = record.get(name);
        return value == null ? "" : value.toString();
    }

    private static void validateAccessIds(List<AccessLog> logs) throws IOException {
        Set<String> seen = new HashSet<>(logs.size());
        for (AccessLog log : logs) {
            if (log.accessId == null || log.accessId.isEmpty()) {
                throw new IOException("empty access_id");
            }
            if (!seen.add(log.accessId)) {
                throw new IOException("duplicate access_id " + log.accessId);
            }
        }
    }

    @SuppressWarnings("unused")
    private static Path toPath(String first, String... more) {
        return Paths.get(first, more);
    }
}
