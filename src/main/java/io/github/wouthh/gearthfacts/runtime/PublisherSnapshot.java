package io.github.wouthh.gearthfacts.runtime;

import java.time.Instant;

public record PublisherSnapshot(
        boolean running,
        long roomId,
        Instant nextAt,
        String lastFact,
        int part,
        int parts,
        String status) {
    public static PublisherSnapshot stopped(String status) {
        return new PublisherSnapshot(false, 0, null, "", 0, 0, status);
    }
}
