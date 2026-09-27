package io.github.wouthh.gearthfacts.runtime;

public final class FactFailure extends RuntimeException {
    public enum Kind {
        AUTHENTICATION,
        TRANSIENT,
        INVALID,
        PERMANENT
    }

    private final Kind kind;

    public FactFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FactFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
