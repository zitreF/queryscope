package me.fertiz.queryscope.agent.loadtest;

import me.fertiz.queryscope.agent.trace.UnitTracer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;

final class LoopbackHttp {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int FIRST_ERROR_STATUS = 400;
    private static final int FIRST_VALID_STATUS = 100;

    private LoopbackHttp() {
    }

    static int send(URI target, long run) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "QueryScope load test");
        connection.setRequestProperty(UnitTracer.LOAD_TEST_HEADER, String.valueOf(run));
        int status = connection.getResponseCode();
        if (status < FIRST_VALID_STATUS) {
            connection.disconnect();
            throw new IOException("the answer was no valid HTTP response");
        }
        InputStream body = status >= FIRST_ERROR_STATUS ? connection.getErrorStream() : connection.getInputStream();
        if (body != null) {
            try (body) {
                body.transferTo(OutputStream.nullOutputStream());
            }
        }
        return status;
    }
}
