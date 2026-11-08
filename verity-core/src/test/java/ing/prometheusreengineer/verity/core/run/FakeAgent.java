package ing.prometheusreengineer.verity.core.run;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A {@code -javaagent} for FakeGame: marks the JVM, and reports over its own hook connection, like a real agent with
 * its own relocated copy of verity-api next to a mod's.
 */
public class FakeAgent {
    public static void premain(String args, java.lang.instrument.Instrumentation inst) throws Exception {
        System.setProperty("fake.agent", "attached");
        Socket s = new Socket("127.0.0.1", Integer.getInteger("verity.port"));
        OutputStream out = s.getOutputStream();
        String token = System.getProperty("verity.token");
        out.write(("{\"type\":\"hello\",\"token\":\"" + token + "\",\"protocol\":1}\n"
                + "{\"type\":\"pass\",\"name\":\"agent connected\"}\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        // Left open, as an agent's connection is for the life of the game.
    }
}
