package dev.verity.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogParserTest {
    @Test
    void rendersXmlEventsAndPassesPlainLines() {
        LogParser p = new LogParser();
        List<String> out = new ArrayList<>();
        for (String l : """
                plain line
                <log4j:Event logger="net.minecraft.client.Minecraft" timestamp="1700000000000" level="WARN" thread="Render thread">
                  <log4j:Message><![CDATA[first
                second]]></log4j:Message>
                  <log4j:Throwable><![CDATA[java.lang.RuntimeException: boom
                	at a.b(C.java:1)
                ]]></log4j:Throwable>
                </log4j:Event>
                after""".split("\n")) {
            out.addAll(p.accept(l));
        }
        assertThat(out.get(0)).isEqualTo("plain line");
        assertThat(out.get(1)).matches("\\[\\d\\d:\\d\\d:\\d\\d] \\[Render thread/WARN] \\(Minecraft\\): first");
        assertThat(out).contains("second", "java.lang.RuntimeException: boom", "after");
    }
}
