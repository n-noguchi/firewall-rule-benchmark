package firewall;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes the results CSV in the canonical benchmark layout. */
final class ResultWriter {
    static final String HEADER = "access_id,selected_policy_id,matched_rule_id,action";

    private ResultWriter() {
    }

    static void write(Path output, List<EvalResult> results) throws IOException {
        Path parent = output.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.write("\n");
            for (EvalResult result : results) {
                writer.write(escape(result.accessId));
                writer.write(",");
                writer.write(escape(result.selectedPolicyId));
                writer.write(",");
                writer.write(escape(result.matchedRuleId == null ? "" : result.matchedRuleId));
                writer.write(",");
                writer.write(escape(result.action));
                writer.write("\n");
            }
            writer.flush();
        }
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (needsQuoting(value)) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static boolean needsQuoting(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }
}
