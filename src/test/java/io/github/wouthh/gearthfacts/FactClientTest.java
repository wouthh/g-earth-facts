package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.wouthh.gearthfacts.runtime.ApiNinjasFactClient;
import io.github.wouthh.gearthfacts.runtime.FactFailure;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    @Test
    void cancellationPropagatesToTheUnderlyingHttpRequest() {
        PendingHttpClient http = new PendingHttpClient();
        CompletableFuture<String> result =
                new ApiNinjasFactClient(http, java.net.URI.create("http://facts.test/facts"))
                        .fetch("demo-key");
        assertTrue(result.cancel(true));
        assertTrue(http.source.isCancelled());
    }

    private static final class PendingHttpClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newHttpClient();
        private final CompletableFuture<HttpResponse<byte[]>> source = new CompletableFuture<>();

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<java.time.Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            return delegate.send(request, handler);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) source;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }
}
