package dev.verity.core.launch;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns game output into readable lines. Mojang's log4j configs print XML events to stdout (meant for the
 * official launcher); those are rendered like the in-game log format. Plain lines pass through.
 */
public final class LogParser {
    private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern MESSAGE = Pattern.compile("<log4j:Message><!\\[CDATA\\[(.*?)]]></log4j:Message>", Pattern.DOTALL);
    private static final Pattern THROWABLE = Pattern.compile("<log4j:Throwable><!\\[CDATA\\[(.*?)]]></log4j:Throwable>", Pattern.DOTALL);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private StringBuilder event;

    /** Feeds one raw output line; returns zero or more display lines. */
    public List<String> accept(String line) {
        List<String> out = new ArrayList<>();
        String trimmed = line.trim();
        if (event == null && trimmed.startsWith("<log4j:Event")) {
            event = new StringBuilder();
        }
        if (event != null) {
            event.append(line).append('\n');
            if (trimmed.endsWith("</log4j:Event>") || event.length() > 4 * 1024 * 1024) {
                out.addAll(render(event.toString()));
                event = null;
            }
            return out;
        }
        out.add(line);
        return out;
    }

    static List<String> render(String xml) {
        List<String> out = new ArrayList<>();
        int headerEnd = xml.indexOf('>');
        String header = headerEnd > 0 ? xml.substring(0, headerEnd) : xml;
        String logger = "";
        String level = "INFO";
        String thread = "";
        String time = "";
        Matcher a = ATTR.matcher(header);
        while (a.find()) {
            switch (a.group(1)) {
                case "logger" -> logger = a.group(2);
                case "level" -> level = a.group(2);
                case "thread" -> thread = a.group(2);
                case "timestamp" -> {
                    try {
                        time = TIME.format(Instant.ofEpochMilli(Long.parseLong(a.group(2))));
                    } catch (NumberFormatException ignored) {
                        // keep empty
                    }
                }
                default -> {
                }
            }
        }
        Matcher m = MESSAGE.matcher(xml);
        String message = m.find() ? m.group(1) : "";
        String shortLogger = logger.contains(".") ? logger.substring(logger.lastIndexOf('.') + 1) : logger;
        String prefix = "[" + time + "] [" + thread + "/" + level + "]" + (shortLogger.isEmpty() ? "" : " (" + shortLogger + ")") + ": ";
        String[] lines = message.split("\n", -1);
        out.add(prefix + lines[0]);
        for (int i = 1; i < lines.length; i++) if (!lines[i].isEmpty()) out.add(lines[i]);
        Matcher t = THROWABLE.matcher(xml);
        if (t.find()) for (String l : t.group(1).split("\n")) if (!l.isBlank()) out.add(l);
        return out;
    }
}
