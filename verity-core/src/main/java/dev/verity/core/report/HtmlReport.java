package dev.verity.core.report;

import dev.verity.core.run.CheckResult;
import dev.verity.core.run.RunReport;
import dev.verity.core.run.Status;
import dev.verity.core.run.TargetResult;
import dev.verity.core.util.McVersion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** A self-contained HTML page: version × loader matrix, then per-target details with checks and log tails. */
public final class HtmlReport {
    private HtmlReport() {
    }

    public static String render(RunReport r) {
        StringBuilder b = new StringBuilder();
        b.append("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Verity report</title>
                <style>
                :root{--bg:#f7f7f8;--fg:#1b1d22;--muted:#5d6370;--card:#fff;--line:#e2e4e9;--pass:#1f8a4c;--fail:#c4302b;--warn:#b26a00;--idle:#7a808c;--code:#f0f1f4}
                @media (prefers-color-scheme: dark){:root{--bg:#121417;--fg:#e7e9ee;--muted:#9aa1ad;--card:#1b1e23;--line:#2c3038;--pass:#3fbf73;--fail:#ff6b63;--warn:#e9a23b;--idle:#8b919c;--code:#23272e}}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
                main{max-width:1100px;margin:0 auto;padding:24px 16px}h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:28px 0 10px}
                .muted{color:var(--muted)}.summary{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}
                .tile{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 14px;min-width:110px}
                .tile b{display:block;font-size:20px}
                .wrap{overflow-x:auto}table{border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden}
                th,td{padding:6px 12px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}
                .s{font-weight:600}.PASSED,.PREPARED{color:var(--pass)}.FAILED,.CRASHED,.ERROR{color:var(--fail)}.TIMED_OUT,.CANCELLED{color:var(--warn)}.none{color:var(--idle)}
                details{background:var(--card);border:1px solid var(--line);border-radius:10px;margin:8px 0;padding:8px 14px}
                summary{cursor:pointer;font-weight:600}pre{background:var(--code);padding:10px;border-radius:8px;overflow-x:auto;font-size:12px;max-height:420px}
                ul.checks{padding-left:18px}a{color:inherit}img{max-width:100%;border-radius:8px;border:1px solid var(--line)}
                </style></head><body><main>
                """);
        b.append("<h1>").append(h(r.profile)).append("</h1>");
        long secs = r.startedAt != null && r.finishedAt != null ? Duration.between(r.startedAt, r.finishedAt).toSeconds() : 0;
        b.append("<div class=\"muted\">").append(r.startedAt == null ? "" : h(r.startedAt.toString())).append(" · ")
                .append(secs / 60).append("m ").append(secs % 60).append("s · ").append(h(r.host)).append("</div>");

        b.append("<div class=\"summary\">");
        tile(b, "Targets", r.results.size(), null);
        tile(b, "Passed", r.passed(), "PASSED");
        tile(b, "Failed", r.count(Status.FAILED), "FAILED");
        tile(b, "Crashed", r.count(Status.CRASHED), "CRASHED");
        tile(b, "Timed out", r.count(Status.TIMED_OUT), "TIMED_OUT");
        tile(b, "Errors", r.count(Status.ERROR), "ERROR");
        b.append("</div>");

        // Matrix
        Set<String> loaders = new LinkedHashSet<>();
        TreeSet<String> versions = new TreeSet<>(McVersion.COMPARATOR.reversed());
        for (TargetResult t : r.results) {
            loaders.add(t.loader);
            versions.add(t.minecraft);
        }
        b.append("<h2>Matrix</h2><div class=\"wrap\"><table><tr><th>Minecraft</th>");
        for (String l : loaders) b.append("<th>").append(h(l)).append("</th>");
        b.append("</tr>");
        for (String v : versions) {
            b.append("<tr><td>").append(h(v)).append("</td>");
            for (String l : loaders) {
                List<TargetResult> cell = new ArrayList<>();
                for (TargetResult t : r.results) if (t.minecraft.equals(v) && t.loader.equals(l)) cell.add(t);
                b.append("<td>");
                if (cell.isEmpty()) b.append("<span class=\"none\">—</span>");
                for (TargetResult t : cell) {
                    b.append("<a class=\"s ").append(t.status).append("\" href=\"#").append(h(t.id)).append("\">")
                            .append(label(t.status)).append("</a> ");
                }
                b.append("</td>");
            }
            b.append("</tr>");
        }
        b.append("</table></div>");

        b.append("<h2>Targets</h2>");
        for (TargetResult t : r.results) {
            b.append("<details id=\"").append(h(t.id)).append("\"").append(t.success() ? "" : " open").append("><summary><span class=\"s ")
                    .append(t.status).append("\">").append(label(t.status)).append("</span> ").append(h(t.id))
                    .append(" <span class=\"muted\">").append(t.durationMs / 1000).append("s</span></summary>");
            if (t.message != null) b.append("<p>").append(h(t.message)).append("</p>");
            b.append("<p class=\"muted\">Loader ").append(h(t.loader)).append(" ").append(h(t.loaderVersion == null ? "" : t.loaderVersion))
                    .append(t.java != null ? " · Java " + h(t.java) : "").append(t.display != null ? " · " + h(t.display) : "")
                    .append(t.exitCode != null ? " · exit " + t.exitCode : "").append("</p>");
            if (!t.mods.isEmpty()) b.append("<p class=\"muted\">Mods: ").append(h(String.join(", ", t.mods))).append("</p>");
            if (!t.checks.isEmpty()) {
                b.append("<ul class=\"checks\">");
                for (CheckResult c : t.checks) {
                    b.append("<li><span class=\"s ").append(c.passed() ? "PASSED" : "FAILED").append("\">").append(c.passed() ? "✔" : "✘")
                            .append("</span> ").append(h(c.name()));
                    if (c.message() != null) b.append(" — ").append(h(c.message()));
                    if (c.detail() != null) b.append("<pre>").append(h(c.detail())).append("</pre>");
                    b.append("</li>");
                }
                b.append("</ul>");
            }
            List<String> links = new ArrayList<>();
            if (t.logFile != null) links.add(link(t.logFile, "raw output"));
            links.add(link("targets/" + t.id + "/console.log", "console log"));
            for (String c : t.crashReports) links.add(link(c, c.substring(c.lastIndexOf('/') + 1)));
            b.append("<p>").append(String.join(" · ", links)).append("</p>");
            for (String a : t.attachments) {
                String lower = a.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                    b.append("<p><img src=\"").append(h(a)).append("\" alt=\"").append(h(a)).append("\"></p>");
                } else {
                    b.append("<p>").append(link(a, "attachment: " + a.substring(a.lastIndexOf('/') + 1))).append("</p>");
                }
            }
            if (!t.verityLog.isEmpty()) b.append("<pre>").append(h(String.join("\n", t.verityLog))).append("</pre>");
            if (!t.logTail.isEmpty()) b.append("<details><summary>Last log lines</summary><pre>").append(h(String.join("\n", t.logTail)))
                    .append("</pre></details>");
            b.append("</details>");
        }
        b.append("</main></body></html>\n");
        return b.toString();
    }

    private static void tile(StringBuilder b, String name, long value, String cls) {
        b.append("<div class=\"tile\"><span class=\"muted\">").append(name).append("</span><b")
                .append(cls != null && value > 0 ? " class=\"" + cls + "\"" : "").append(">").append(value).append("</b></div>");
    }

    private static String label(Status s) {
        if (s == null) return "?";
        return switch (s) {
            case PASSED -> "✔ passed";
            case PREPARED -> "✔ prepared";
            case FAILED -> "✘ failed";
            case CRASHED -> "✘ crashed";
            case TIMED_OUT -> "⏱ timed out";
            case ERROR -> "⚠ error";
            case CANCELLED -> "■ cancelled";
            default -> s.name().toLowerCase(Locale.ROOT);
        };
    }

    private static String link(String href, String text) {
        return "<a href=\"" + h(href) + "\">" + h(text) + "</a>";
    }

    static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
