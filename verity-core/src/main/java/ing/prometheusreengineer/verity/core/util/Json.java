package ing.prometheusreengineer.verity.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared, lenient Jackson configuration. */
public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private Json() {
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.readValue(in, type);
        }
    }

    public static JsonNode tree(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.readTree(in);
        }
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(Path file, Object value) throws IOException {
        FileUtil.writeAtomically(file, MAPPER.writeValueAsBytes(value));
    }

    public static String text(JsonNode node, String field) {
        JsonNode n = node == null ? null : node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
