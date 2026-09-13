package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.wouthh.gearthfacts.runtime.ApiNinjasFactClient;
import io.github.wouthh.gearthfacts.runtime.FactFailure;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FactClientTest {
    @Test
    void callsDocumentedEndpointWithKeyAndParsesFact() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/facts",
                    exchange -> {
                        assertEquals(
                                "demo-key", exchange.getRequestHeaders().getFirst("X-Api-Key"));
                        assertEquals("GET", exchange.getRequestMethod());
                        byte[] response =
                                "[{\"fact\":\"Octopuses have three hearts\"}]"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, response.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(response);
                        }
                    });
            server.start();
            var endpoint =
                    java.net.URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/facts");
            String fact =
                    new ApiNinjasFactClient(HttpClient.newHttpClient(), endpoint)
                            .fetch("demo-key")
                            .get(3, TimeUnit.SECONDS);
            assertEquals("Octopuses have three hearts", fact);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesApiFailuresWithoutLeakingBody() {
        FactFailure auth =
                assertThrows(
                        FactFailure.class,
                        () -> ApiNinjasFactClient.parseResponse(401, "secret details"));
        assertEquals(FactFailure.Kind.AUTHENTICATION, auth.kind());
        assertFalse(auth.getMessage().contains("secret"));
        assertEquals(
                FactFailure.Kind.TRANSIENT,
                assertThrows(FactFailure.class, () -> ApiNinjasFactClient.parseResponse(429, ""))
                        .kind());
        assertEquals(
                FactFailure.Kind.INVALID,
                assertThrows(FactFailure.class, () -> ApiNinjasFactClient.parseResponse(200, "[]"))
                        .kind());
    }
}
