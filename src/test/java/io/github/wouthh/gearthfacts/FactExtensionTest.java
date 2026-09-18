package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.wouthh.gearthfacts.runtime.PublisherSnapshot;
import io.github.wouthh.gearthfacts.runtime.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FactExtensionTest {
    @Test
    void queuedUiUpdatesApplyOnlyTheLatestSnapshot() {
        PublisherSnapshot first = PublisherSnapshot.stopped("first");
        PublisherSnapshot second = PublisherSnapshot.stopped("second");

        assertTrue(FactExtension.isLatestSnapshot(first, first));
        assertFalse(FactExtension.isLatestSnapshot(second, first));
    }

    @Test
    void failedSettingsLoadReleasesTheProfileLock() throws Exception {
        Path directory = Files.createTempDirectory("facts-constructor-lock-");
        Files.writeString(directory.resolve("settings.properties"), "x".repeat(16_385));
        String previous = System.getProperty("gearthfacts.stateDir");
        System.setProperty("gearthfacts.stateDir", directory.toString());
        try {
            assertThrows(IOException.class, () -> new FactExtension(new String[0]));
            try (SettingsStore reopened = new SettingsStore(directory)) {
                assertEquals(directory.toAbsolutePath().normalize(), reopened.directory());
            }
        } finally {
            if (previous == null) System.clearProperty("gearthfacts.stateDir");
            else System.setProperty("gearthfacts.stateDir", previous);
        }
    }
}
