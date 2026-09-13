package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.wouthh.gearthfacts.runtime.PublisherSnapshot;
import org.junit.jupiter.api.Test;

class FactExtensionTest {
    @Test
    void queuedUiUpdatesApplyOnlyTheLatestSnapshot() {
        PublisherSnapshot first = PublisherSnapshot.stopped("first");
        PublisherSnapshot second = PublisherSnapshot.stopped("second");

        assertTrue(FactExtension.isLatestSnapshot(first, first));
        assertFalse(FactExtension.isLatestSnapshot(second, first));
    }
}
