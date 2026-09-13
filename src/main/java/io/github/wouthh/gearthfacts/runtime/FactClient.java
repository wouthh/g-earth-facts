package io.github.wouthh.gearthfacts.runtime;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface FactClient {
    CompletableFuture<String> fetch(String apiKey);
}
