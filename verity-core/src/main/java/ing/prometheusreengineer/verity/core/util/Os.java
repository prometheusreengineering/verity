package ing.prometheusreengineer.verity.core.util;

import java.util.Locale;

/** Host operating system as Mojang's version JSON rules name it. */
public enum Os {
    WINDOWS("windows"), LINUX("linux"), OSX("osx");

    private final String mojangName;

    Os(String mojangName) {
        this.mojangName = mojangName;
    }

    public String mojangName() {
        return mojangName;
    }

    public static Os current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return OSX;
        return LINUX;
    }

    /** Normalized CPU architecture: {@code x86_64}, {@code x86}, {@code arm64} or {@code arm32}. */
    public static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (a) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "aarch64", "arm64" -> "arm64";
            default -> a.startsWith("arm") ? "arm32" : a;
        };
    }

    public static boolean is64Bit() {
        String arch = arch();
        return arch.equals("x86_64") || arch.equals("arm64");
    }

    public String classpathSeparator() {
        return this == WINDOWS ? ";" : ":";
    }

    public String javaExecutable(boolean console) {
        return this == WINDOWS ? (console ? "java.exe" : "javaw.exe") : "java";
    }
}
