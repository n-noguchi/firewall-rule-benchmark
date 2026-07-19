package firewall.spark;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON helpers backed by Jackson (provided by the Spark classpath). */
final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static byte[] toBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw newUnchecked(e);
        }
    }

    static void write(OutputStream output, Object value) throws IOException {
        MAPPER.writeValue(output, value);
    }

    static <T> T parse(InputStream input, Class<T> type) throws IOException {
        return MAPPER.readValue(input, type);
    }

    static <T> T parse(byte[] bytes, Class<T> type) throws IOException {
        return MAPPER.readValue(bytes, type);
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("error", message);
        return body;
    }

    static Map<String, Object> status(String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", value);
        return body;
    }

    private static RuntimeException newUnchecked(IOException e) {
        return new RuntimeException("json serialization failed: " + e.getMessage(), e);
    }
}
