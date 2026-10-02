package dev.verity.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class VerityCliTest {
    @TempDir
    Path tmp;

    record Result(int code, String out, String err) {
    }

    Result run(String... args) {
        CommandLine cl = VerityCli.commandLine();
        cl.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cl.setOut(new PrintWriter(out));
        cl.setErr(new PrintWriter(err));
        String[] full = new String[args.length + 1];
        full[0] = args[0];
        full[1] = "--home=" + tmp.resolve("home");
        System.arraycopy(args, 1, full, 2, args.length - 1);
        int code = cl.execute(full);
        return new Result(code, out.toString(), err.toString());
    }

    @Test
    void profileLifecycleAndPlan() throws Exception {
        Path mod = tmp.resolve("mod.jar");
        try (JarOutputStream j = new JarOutputStream(Files.newOutputStream(mod))) {
            j.putNextEntry(new ZipEntry("fabric.mod.json"));
            j.write("{\"id\":\"m\",\"depends\":{\"minecraft\":\"1.20.1\"}}".getBytes());
        }
        Result created = run("profile", "new", "demo", "-t", "1.20.1:fabric", "-t", "1.8.9:forge", "-m", mod.toString());
        assertThat(created.code()).as(created.err()).isZero();
        assertThat(run("profile", "list").out()).contains("demo");
        assertThat(run("profile", "show", "demo").out()).contains("\"loader\" : \"fabric\"");
        assertThat(run("profile", "new", "demo").code()).isEqualTo(VerityCli.CONFIG_ERROR);

        Result plan = run("plan", "demo", "-v");
        assertThat(plan.code()).as(plan.err()).isZero();
        assertThat(plan.out()).contains("1.20.1-fabric").contains("+ mod.jar").contains("1.8.9-forge").contains("- mod.jar");

        Path exported = tmp.resolve("exported.json");
        assertThat(run("profile", "export", "demo", exported.toString()).code()).isZero();
        assertThat(exported).exists();
        assertThat(run("profile", "rm", "demo").code()).isZero();
        assertThat(run("profile", "import", exported.toString()).code()).isZero();
        assertThat(run("cache", "info").out()).contains("libraries");
    }

    @Test
    void configErrorsExitWith2() {
        Result r = run("run", "does-not-exist");
        assertThat(r.code()).isEqualTo(VerityCli.CONFIG_ERROR);
        assertThat(r.err()).contains("No profile named");
        assertThat(run("profile", "new", "x", "-t", "1.20.1:quilt").code()).isEqualTo(VerityCli.CONFIG_ERROR);
    }
}
