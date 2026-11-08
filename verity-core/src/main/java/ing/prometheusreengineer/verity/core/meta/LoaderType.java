package ing.prometheusreengineer.verity.core.meta;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum LoaderType {
    VANILLA, FABRIC, FORGE, NEOFORGE;

    @JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return switch (this) {
            case VANILLA -> "Vanilla";
            case FABRIC -> "Fabric";
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
        };
    }

    @JsonCreator
    public static LoaderType parse(String s) {
        String n = s.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (n) {
            case "vanilla", "none", "minecraft" -> VANILLA;
            case "fabric" -> FABRIC;
            case "forge", "minecraftforge" -> FORGE;
            case "neoforge", "neo" -> NEOFORGE;
            default -> throw new IllegalArgumentException("Unknown loader '" + s + "' (expected vanilla, fabric, forge or neoforge)");
        };
    }
}
