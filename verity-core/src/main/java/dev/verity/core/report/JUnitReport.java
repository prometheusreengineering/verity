package dev.verity.core.report;

import dev.verity.core.run.CheckResult;
import dev.verity.core.run.RunReport;
import dev.verity.core.run.Status;
import dev.verity.core.run.TargetResult;
import java.time.Duration;
import java.util.Locale;

/** JUnit XML: one suite per target, a "client" testcase for the run itself plus one testcase per reported check. */
public final class JUnitReport {
    private JUnitReport() {
    }

    public static String render(RunReport r) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        int tests = 0;
        int failures = 0;
        int errors = 0;
        for (TargetResult t : r.results) {
            tests += 1 + t.checks.size();
            failures += (int) t.checks.stream().filter(c -> !c.passed()).count();
            if (!t.success()) {
                if (t.status == Status.ERROR || t.status == Status.CANCELLED) errors++;
                else failures++;
            }
        }
        double total = r.startedAt != null && r.finishedAt != null ? Duration.between(r.startedAt, r.finishedAt).toMillis() / 1000.0 : 0;
        b.append("<testsuites name=\"").append(x("verity " + r.profile)).append("\" tests=\"").append(tests)
                .append("\" failures=\"").append(failures).append("\" errors=\"").append(errors)
                .append("\" time=\"").append(secs(total)).append("\">\n");
        for (TargetResult t : r.results) suite(b, r, t);
        b.append("</testsuites>\n");
        return b.toString();
    }

    private static void suite(StringBuilder b, RunReport r, TargetResult t) {
        long checkFailures = t.checks.stream().filter(c -> !c.passed()).count();
        boolean clientFailed = !t.success();
        boolean error = t.status == Status.ERROR || t.status == Status.CANCELLED;
        String cls = "verity." + t.id;
        b.append("  <testsuite name=\"").append(x(t.id)).append("\" tests=\"").append(1 + t.checks.size())
                .append("\" failures=\"").append(checkFailures + (clientFailed && !error ? 1 : 0))
                .append("\" errors=\"").append(error ? 1 : 0)
                .append("\" time=\"").append(secs(t.durationMs / 1000.0)).append("\"");
        if (r.startedAt != null) b.append(" timestamp=\"").append(r.startedAt.toString()).append("\"");
        b.append(">\n    <properties>\n");
        prop(b, "minecraft", t.minecraft);
        prop(b, "loader", t.loader);
        prop(b, "loaderVersion", t.loaderVersion);
        prop(b, "status", t.status == null ? null : t.status.name());
        prop(b, "java", t.java);
        prop(b, "display", t.display);
        b.append("    </properties>\n");

        b.append("    <testcase classname=\"").append(x(cls)).append("\" name=\"client\" time=\"").append(secs(t.durationMs / 1000.0)).append("\"");
        if (t.success()) {
            b.append("/>\n");
        } else {
            String tag = error ? "error" : "failure";
            b.append(">\n      <").append(tag).append(" type=\"").append(t.status).append("\" message=\"").append(x(t.message)).append("\">")
                    .append(x(String.join("\n", t.logTail))).append("</").append(tag).append(">\n    </testcase>\n");
        }
        for (CheckResult c : t.checks) {
            b.append("    <testcase classname=\"").append(x(cls)).append("\" name=\"").append(x(c.name())).append("\" time=\"0\"");
            if (c.passed()) {
                b.append("/>\n");
            } else {
                b.append(">\n      <failure message=\"").append(x(c.message())).append("\">").append(x(c.detail() != null ? c.detail() : c.message()))
                        .append("</failure>\n    </testcase>\n");
            }
        }
        if (!t.verityLog.isEmpty()) b.append("    <system-out>").append(x(String.join("\n", t.verityLog))).append("</system-out>\n");
        b.append("  </testsuite>\n");
    }

    private static void prop(StringBuilder b, String name, String value) {
        if (value == null) return;
        b.append("      <property name=\"").append(name).append("\" value=\"").append(x(value)).append("\"/>\n");
    }

    private static String secs(double s) {
        return String.format(Locale.ROOT, "%.3f", s);
    }

    /** XML-escapes text and drops characters XML 1.0 cannot carry. */
    static String x(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> {
                    if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c < 0xD800) || (c >= 0xE000 && c <= 0xFFFD)
                            || Character.isSurrogate(c)) b.append(c);
                }
            }
        }
        return b.toString();
    }
}
