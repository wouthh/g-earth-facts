package io.github.wouthh.gearthfacts.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.json.JSONArray;
import org.json.JSONObject;

/** HTTP boundary for API Ninjas' documented random-facts endpoint. */
public final class ApiNinjasFactClient implements FactClient {
    public static final URI ENDPOINT = URI.create("https://api.api-ninjas.com/v1/facts");
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private final HttpClient client;
    private final URI endpoint;

    public ApiNinjasFactClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), ENDPOINT);
    }

    public ApiNinjasFactClient(HttpClient client, URI endpoint) {
        this.client = java.util.Objects.requireNonNull(client);
        this.endpoint = java.util.Objects.requireNonNull(endpoint);
    }

    @Override
    public CompletableFuture<String> fetch(String apiKey) {
        if (apiKey == null || apiKey.isBlank())
            return CompletableFuture.failedFuture(
                    new FactFailure(FactFailure.Kind.AUTHENTICATION, "An API key is required"));
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", apiKey)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        CompletableFuture<HttpResponse<byte[]>> source =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        CompletableFuture<String> result =
                new CompletableFuture<>() {
                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        boolean cancelled = super.cancel(mayInterruptIfRunning);
                        source.cancel(mayInterruptIfRunning);
                        return cancelled;
                    }
                };
        source.whenComplete(
                (response, error) -> {
                    if (error != null) {
                        result.completeExceptionally(classify(error));
                        return;
                    }
                    try {
                        byte[] body = response.body();
                        if (body == null || body.length > MAX_RESPONSE_BYTES)
                            throw new FactFailure(
                                    FactFailure.Kind.INVALID, "API response is too large");
                        result.complete(
                                parseResponse(
                                        response.statusCode(),
                                        new String(body, java.nio.charset.StandardCharsets.UTF_8)));
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    }
                });
        return result;
    }

    public static String parseResponse(int status, String body) {
        if (status == 401 || status == 403)
            throw new FactFailure(
                    FactFailure.Kind.AUTHENTICATION, "API Ninjas rejected the API key");
        if (status == 429 || status >= 500)
            throw new FactFailure(
                    FactFailure.Kind.TRANSIENT,
                    "API Ninjas is temporarily unavailable (HTTP " + status + ")");
        if (status < 200 || status >= 300)
            throw new FactFailure(FactFailure.Kind.PERMANENT, "API Ninjas returned HTTP " + status);
        if (body == null
                || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > MAX_RESPONSE_BYTES)
            throw new FactFailure(FactFailure.Kind.INVALID, "API response is too large");
        try {
            JSONArray facts = new JSONArray(body);
            if (facts.isEmpty())
                throw new FactFailure(FactFailure.Kind.INVALID, "API response contained no facts");
            JSONObject first = facts.optJSONObject(0);
            String fact = first == null ? null : first.optString("fact", null);
            if (fact == null || fact.isBlank() || fact.length() > 16_384)
                throw new FactFailure(
                        FactFailure.Kind.INVALID, "API response contained no usable fact");
            return fact;
        } catch (FactFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FactFailure(
                    FactFailure.Kind.INVALID, "API response was not a facts array", e);
        }
    }

    private static FactFailure classify(Throwable error) {
        Throwable cause =
                error instanceof CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error;
        return new FactFailure(FactFailure.Kind.TRANSIENT, "Could not reach API Ninjas", cause);
    }
}
