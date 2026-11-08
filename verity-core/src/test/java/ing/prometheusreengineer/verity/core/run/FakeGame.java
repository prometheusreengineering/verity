package ing.prometheusreengineer.verity.core.run;

import ing.prometheusreengineer.verity.api.Verity;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pretends to be Minecraft: behaves according to the version name it is launched with. */
public class FakeGame {
    public static void main(String[] args) throws Exception {
        String version = "";
        String gameDir = ".";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--version")) version = args[i + 1];
            if (args[i].equals("--gameDir")) gameDir = args[i + 1];
        }
        System.out.println("Fake game " + version + " starting, user.home=" + System.getProperty("user.home"));
        System.out.println("<log4j:Event logger=\"net.minecraft.client.Minecraft\" timestamp=\"1700000000000\" level=\"INFO\" thread=\"Render thread\">");
        System.out.println("  <log4j:Message><![CDATA[Setting user: Verity]]></log4j:Message>");
        System.out.println("</log4j:Event>");
        java.io.File[] mods = new java.io.File(gameDir, "mods").listFiles();
        if (mods != null) {
            java.util.Arrays.sort(mods);
            StringBuilder names = new StringBuilder();
            for (java.io.File m : mods) names.append(' ').append(m.getName());
            System.out.println("mods:" + names);
        }
        // Loader versions (fabric-loader-<v>-<mc>) behave like their base version.
        switch (version.replaceFirst("^fabric-loader-[^-]+-", "")) {
            case "fake-pass" -> {
                Verity.test("mods-dir-exists", () -> {
                    if (!Files.isDirectory(Path.of("mods"))) throw new AssertionError("no mods dir");
                });
                Verity.pass("loaded");
                Verity.log("hello from " + Verity.target());
                Verity.attach("note.txt", "attached".getBytes());
                Verity.finish();
                Thread.sleep(60_000);
            }
            case "fake-fail" -> {
                Verity.pass("loaded");
                Verity.fail("patched", "class was not transformed");
                Verity.finish();
                Thread.sleep(60_000);
            }
            case "fake-crash" -> {
                Verity.pass("loaded");
                Path crash = Path.of(gameDir, "crash-reports", "crash-2026-01-01_00.00.00-client.txt");
                Files.createDirectories(crash.getParent());
                Files.writeString(crash, "---- Minecraft Crash Report ----\n\nDescription: Initializing game\n\njava.lang.NoSuchMethodError: foo\n");
                System.exit(-1);
            }
            case "fake-agent" -> {
                Verity.pass("agent " + System.getProperty("fake.agent"));
                Verity.finish();
                Thread.sleep(60_000);
            }
            case "fake-idle" -> Thread.sleep(60_000); // vanilla-like: never talks to Verity
            case "fake-nobackend" -> {
                // 26.3+ with no working renderer: both backends fail, then the game waits on a dialog forever.
                Verity.pass("loaded");
                System.out.println("[Render thread/ERROR] [minecraft/Minecraft]: Failed to create backend OpenGL");
                System.out.println("com.mojang.renderpearl.api.device.BackendCreationException: Couldn't find matching GLX visual");
                System.out.println("[Render thread/ERROR] [minecraft/Minecraft]: Failed to create backend Vulkan");
                System.out.flush();
                Thread.sleep(60_000);
            }
            default -> System.exit(0); // exits without finish
        }
    }
}
