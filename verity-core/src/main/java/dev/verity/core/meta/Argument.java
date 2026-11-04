package dev.verity.core.meta;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import dev.verity.core.util.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A modern-format launch argument: a plain string, or {@code {rules, value}} where value is a string or list. */
@JsonDeserialize(using = Argument.Deserializer.class)
public record Argument(List<Rule> rules, List<String> values) {

    public static Argument plain(String value) {
        return new Argument(List.of(), List.of(value));
    }

    static final class Deserializer extends JsonDeserializer<Argument> {
        @Override
        public Argument deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();
            if (node.isTextual()) return plain(node.asText());
            List<Rule> rules = new ArrayList<>();
            if (node.has("rules")) {
                for (JsonNode r : node.get("rules")) rules.add(Json.MAPPER.treeToValue(r, Rule.class));
            }
            List<String> values = new ArrayList<>();
            JsonNode v = node.get("value");
            if (v == null) v = node.get("values");
            if (v != null && v.isArray()) for (JsonNode x : v) values.add(x.asText());
            else if (v != null) values.add(v.asText());
            return new Argument(rules, values);
        }
    }
}
