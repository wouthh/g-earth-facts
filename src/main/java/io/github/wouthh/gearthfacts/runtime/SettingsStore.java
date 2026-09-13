package io.github.wouthh.gearthfacts.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/** Owner-local settings with an atomic replacement and one extension instance per profile. */
public final class SettingsStore implements AutoCloseable {
    private static final long MAX_BYTES = 16_384;
    private final Path directory;
    private final Path settingsFile;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public SettingsStore(Path directory) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory))
            throw new IOException("Settings directory must not be a symlink");
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        privatePermissions(this.directory, true);
        Path lockFile = this.directory.resolve("settings.lock");
        if (Files.isSymbolicLink(lockFile))
            throw new IOException("Settings lock must not be a symlink");
        lockChannel =
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        privatePermissions(lockFile, false);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            throw new IOException("Another G-Earth Facts instance owns this profile");
        }
        if (lock == null) {
            lockChannel.close();
            throw new IOException("Another G-Earth Facts instance owns this profile");
        }
        settingsFile = this.directory.resolve("settings.properties");
    }

    public static Path defaultDirectory() {
        String configured = System.getProperty("gearthfacts.stateDir");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank())
            return Path.of(localAppData, "G-Earth Facts");
        return Path.of(System.getProperty("user.home"), ".local", "state", "g-earth-facts");
    }

    public synchronized Settings load() throws IOException {
        if (!Files.exists(settingsFile, LinkOption.NOFOLLOW_LINKS)) return new Settings("", "");
        if (Files.isSymbolicLink(settingsFile)
                || !Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(settingsFile) > MAX_BYTES)
            throw new IOException("Invalid G-Earth Facts settings file");
        Properties properties = new Properties();
        try (var input = Files.newInputStream(settingsFile)) {
            // Properties' stream form is ISO-8859-1 with Unicode escapes.  Keep the
            // same format on both sides so a Latin-1 prefix survives a restart.
            properties.load(input);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid G-Earth Facts settings encoding", e);
        }
        return new Settings(
                properties.getProperty("apiKey", ""), properties.getProperty("prefix", ""));
    }

    public synchronized void save(Settings settings) throws IOException {
        if (settings == null) throw new IllegalArgumentException("settings");
        if (settings.apiKey().length() > 512
                || settings.prefix().getBytes(StandardCharsets.UTF_8).length > 8_192)
            throw new IOException("Settings value is too long");
        Properties properties = new Properties();
        properties.setProperty("schema", "1");
        properties.setProperty("apiKey", settings.apiKey());
        properties.setProperty("prefix", settings.prefix());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, "G-Earth Facts local settings");
        atomicWrite(settingsFile, output.toByteArray());
    }

    private void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, ".settings-", ".tmp");
        try {
            privatePermissions(temporary, false);
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            privatePermissions(target, false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void privatePermissions(Path path, boolean directory) {
        try {
            Set<PosixFilePermission> permissions =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs are enforced by the profile; POSIX modes are best effort there.
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void close() throws IOException {
        lock.release();
        lockChannel.close();
    }
}
