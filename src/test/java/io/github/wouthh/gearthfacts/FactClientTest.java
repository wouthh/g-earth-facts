package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.wouthh.gearthfacts.runtime.ApiNinjasFactClient;
import io.github.wouthh.gearthfacts.runtime.FactFailure;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
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

    @Test
    void cancellationClosesAResponseBodyThatIsAlreadyBeingRead() throws Exception {
        PendingHttpClient http = new PendingHttpClient();
        BlockingInputStream body = new BlockingInputStream();
        CompletableFuture<String> result =
                new ApiNinjasFactClient(http, URI.create("http://facts.test/facts"))
                        .fetch("demo-key");
        Thread completion =
                new Thread(
                        () -> http.source.complete(new TestResponse(body)), "facts-test-response");
        completion.start();
        assertTrue(body.started.await(1, TimeUnit.SECONDS));
        assertTrue(result.cancel(true));
        assertTrue(body.closed.await(1, TimeUnit.SECONDS));
        completion.join(1_000);
        assertFalse(completion.isAlive());
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            started.countDown();
            try {
                if (!closed.await(2, TimeUnit.SECONDS))
                    throw new IOException("test stream did not close");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("test stream interrupted", e);
            }
            return -1;
        }

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class TestResponse implements HttpResponse<InputStream> {
        private final InputStream body;

        private TestResponse(InputStream body) {
            this.body = body;
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://facts.test/facts");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class PendingHttpClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newHttpClient();
        private final CompletableFuture<HttpResponse<InputStream>> source =
                new CompletableFuture<>();

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
