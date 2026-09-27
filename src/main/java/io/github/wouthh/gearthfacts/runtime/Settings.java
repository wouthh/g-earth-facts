package io.github.wouthh.gearthfacts.runtime;

/** The two user-editable values; the prefix is intentionally whitespace-sensitive. */
public record Settings(String apiKey, String prefix) {
    public Settings {
        apiKey = apiKey == null ? "" : apiKey.trim();
        prefix = prefix == null ? "" : prefix;
    }
}
