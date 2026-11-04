package dev.verity.core.report;

import dev.verity.core.run.RunReport;
import dev.verity.core.util.FileUtil;
import dev.verity.core.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class Reports {
    public static final String JSON = "report.json";
    public static final String JUNIT = "junit.xml";
    public static final String HTML = "report.html";

    private Reports() {
    }

    public static void writeAll(RunReport report, Path dir) throws IOException {
        Json.write(dir.resolve(JSON), report);
        FileUtil.writeAtomically(dir.resolve(JUNIT), JUnitReport.render(report).getBytes(StandardCharsets.UTF_8));
        FileUtil.writeAtomically(dir.resolve(HTML), HtmlReport.render(report).getBytes(StandardCharsets.UTF_8));
    }

    public static RunReport read(Path dir) throws IOException {
        return Json.read(dir.resolve(JSON), RunReport.class);
    }
}
