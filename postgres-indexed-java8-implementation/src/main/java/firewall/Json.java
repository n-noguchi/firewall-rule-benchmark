package firewall;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Jackson-backed JSON helpers shared by HTTP handlers. */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static <T> T parse(InputStream input, Class<T> type) throws IOException {
        return MAPPER.readValue(input, type);
    }

    static byte[] toBytes(Object value) throws IOException {
        return MAPPER.writeValueAsBytes(value);
    }

    static void write(OutputStream output, int status, Object body) throws IOException {
        byte[] payload = toBytes(body);
        output.write(payload);
        output.flush();
    }

    static Map<String, String> error(String message) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("error", message);
        return Collections.unmodifiableMap(m);
    }

    static Map<String, String> status(String value) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("status", value);
        return Collections.unmodifiableMap(m);
    }
}
