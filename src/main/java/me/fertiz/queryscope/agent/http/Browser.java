package me.fertiz.queryscope.agent.http;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class Browser {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");

    private Browser() {
    }

    public static void open(String url) {
        try {
            new ProcessBuilder(command(url))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not open " + url + " in a browser: " + e.getMessage());
        }
    }

    static List<String> command(String url) {
        String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (system.contains("win")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", url);
        }
        if (system.contains("mac")) {
            return List.of("open", url);
        }
        return List.of("xdg-open", url);
    }
}
