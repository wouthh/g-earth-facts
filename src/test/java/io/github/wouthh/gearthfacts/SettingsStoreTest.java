package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.gearthfacts.runtime.Settings;
import io.github.wouthh.gearthfacts.runtime.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SettingsStoreTest {
    @Test
    void defaultsToEmptyPrefixAndPersistsExactPrefix() throws Exception {
        Path directory = Files.createTempDirectory("facts-settings-");
        SettingsStore store = new SettingsStore(directory);
        assertEquals(new Settings("", ""), store.load());
        store.save(new Settings("secret", "Did you know?  "));
        store.close();
        try (SettingsStore reopened = new SettingsStore(directory)) {
            assertEquals(new Settings("secret", "Did you know?  "), reopened.load());
            reopened.save(new Settings("secret", ""));
        }
        try (SettingsStore cleared = new SettingsStore(directory)) {
            assertEquals("", cleared.load().prefix());
        }
    }

    @Test
    void profileIsSingleOwner() throws Exception {
        Path directory = Files.createTempDirectory("facts-lock-");
        try (SettingsStore first = new SettingsStore(directory)) {
            assertThrows(IOException.class, () -> new SettingsStore(directory));
        }
    }
}
