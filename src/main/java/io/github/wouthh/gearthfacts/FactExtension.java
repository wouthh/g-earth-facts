package io.github.wouthh.gearthfacts;

import gearth.extensions.Extension;
import gearth.extensions.ExtensionInfo;
import gearth.protocol.HMessage;
import gearth.protocol.HPacket;
import gearth.protocol.HPacketFormat;
import io.github.wouthh.gearthfacts.protocol.OriginsProtocol;
import io.github.wouthh.gearthfacts.runtime.ApiNinjasFactClient;
import io.github.wouthh.gearthfacts.runtime.FactScheduler;
import io.github.wouthh.gearthfacts.runtime.PublisherSnapshot;
import io.github.wouthh.gearthfacts.runtime.Settings;
import io.github.wouthh.gearthfacts.runtime.SettingsStore;
import io.github.wouthh.gearthfacts.ui.FactsWindow;
import java.io.IOException;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

@ExtensionInfo(
        Title = "G-Earth Facts",
        Author = "Wout H.",
        Version = "0.1.0",
        Description = "Shout a random API Ninjas fact in Habbo Origins every ten minutes.")
public final class FactExtension extends Extension implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SettingsStore settingsStore;
    private final ApiNinjasFactClient factClient;
    private final ScheduledExecutorService executor;
    private final FactScheduler scheduler;
    private final Object lifecycleLock = new Object();
    private final Object connectionLock = new Object();
    private final Object schedulerTransitionLock = new Object();
    private final Object snapshotLock = new Object();
    private volatile Settings settings;
    private volatile boolean origins;
    private volatile long roomId;
    private volatile FactsWindow window;
    private volatile PublisherSnapshot latest = PublisherSnapshot.stopped("Waiting for Origins");
    private long connectionGeneration;
    private long roomGeneration;

    public FactExtension(String[] args) throws IOException {
        super(args);
        settingsStore = new SettingsStore(SettingsStore.defaultDirectory());
        settings = settingsStore.load();
        factClient = new ApiNinjasFactClient();
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "g-earth-facts-scheduler");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler =
                new FactScheduler(
                        factClient,
                        this::sendShout,
                        executor,
                        FactScheduler.DEFAULT_INTERVAL,
                        FactScheduler.DEFAULT_PART_DELAY,
                        this::publish);
        onConnect(
                (host, port, version, identifier, client) -> {
                    synchronized (schedulerTransitionLock) {
                        // A reconnect is a new publishing generation.  Requiring a fresh Start
                        // avoids carrying a request or countdown across connection boundaries.
                        final long connectionToken;
                        synchronized (connectionLock) {
                            connectionToken = ++connectionGeneration;
                            roomGeneration++;
                            origins = false;
                            roomId = 0;
                        }
                        scheduler.disconnect();
                        boolean connectedToOrigins = OriginsProtocol.isOrigins(host, client);
                        synchronized (connectionLock) {
                            if (connectionGeneration != connectionToken) return;
                            origins = connectedToOrigins;
                            roomId = 0;
                        }
                        publish(
                                new PublisherSnapshot(
                                        false,
                                        0,
                                        null,
                                        latest.lastFact(),
                                        0,
                                        0,
                                        connectedToOrigins
                                                ? "Connected; waiting for a room"
                                                : "Unsupported client; Origins/Shockwave is required"));
                    }
                });
        intercept(HMessage.Direction.TOCLIENT, OriginsProtocol.ROOM_READY_HEADER, this::roomReady);
        intercept(HMessage.Direction.TOSERVER, OriginsProtocol.QUIT_HEADER, this::navigation);
        intercept(HMessage.Direction.TOSERVER, OriginsProtocol.GOTO_FLAT_HEADER, this::navigation);
    }

    private boolean sendShout(HPacket packet) {
        synchronized (connectionLock) {
            if (closed.get()
                    || !origins
                    || roomId <= 0
                    || packet.getFormat() != HPacketFormat.WEDGIE_OUTGOING
                    || packet.headerId() != OriginsProtocol.SHOUT_HEADER) return false;
            try {
                return sendToServer(packet);
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    private void roomReady(HMessage message) {
        synchronized (schedulerTransitionLock) {
            final long connectionToken;
            if (message.isBlocked()) {
                synchronized (connectionLock) {
                    if (!origins) return;
                    roomGeneration++;
                    roomId = 0;
                    connectionToken = connectionGeneration;
                }
                scheduler.roomChanged(0);
                synchronized (connectionLock) {
                    if (connectionGeneration != connectionToken) return;
                }
                return;
            }
            OptionalLong parsed = OriginsProtocol.roomId(message.getPacket());
            if (parsed.isEmpty()) {
                synchronized (connectionLock) {
                    if (!origins) return;
                    roomGeneration++;
                    roomId = 0;
                    connectionToken = connectionGeneration;
                }
                scheduler.roomChanged(0);
                publishStatus("Malformed room context; publishing paused");
                return;
            }
            long newRoomId = parsed.getAsLong();
            final long roomToken;
            final long previousRoomId;
            synchronized (connectionLock) {
                if (!origins) return;
                connectionToken = connectionGeneration;
                previousRoomId = roomId;
                if (previousRoomId != newRoomId) {
                    roomToken = ++roomGeneration;
                    // Invalidate the send guard before waiting for scheduler cancellation.
                    roomId = 0;
                } else {
                    roomToken = roomGeneration;
                }
            }
            synchronized (connectionLock) {
                if (connectionGeneration != connectionToken || roomGeneration != roomToken) return;
            }
            if (previousRoomId != newRoomId) {
                scheduler.roomChanged(0);
                scheduler.roomChanged(newRoomId);
                synchronized (connectionLock) {
                    if (connectionGeneration != connectionToken || roomGeneration != roomToken)
                        return;
                    origins = true;
                    roomId = newRoomId;
                }
                publishStatus("Room " + newRoomId + " is ready");
                return;
            }
            scheduler.roomChanged(newRoomId);
            synchronized (connectionLock) {
                if (connectionGeneration != connectionToken || roomGeneration != roomToken) return;
            }
            publishStatus("Room " + newRoomId + " is ready");
        }
    }

    private void navigation(HMessage message) {
        synchronized (schedulerTransitionLock) {
            final long connectionToken;
            synchronized (connectionLock) {
                if (!origins || message.isBlocked()) return;
                roomGeneration++;
                roomId = 0;
                connectionToken = connectionGeneration;
            }
            scheduler.roomChanged(0);
            synchronized (connectionLock) {
                if (connectionGeneration != connectionToken) return;
            }
            publishStatus("Leaving room; waiting for the next room");
        }
    }

    @Override
    public void onEndConnection() {
        synchronized (schedulerTransitionLock) {
            synchronized (connectionLock) {
                connectionGeneration++;
                roomGeneration++;
                origins = false;
                roomId = 0;
            }
            scheduler.disconnect();
        }
        publishStatus("Disconnected; publishing stopped");
    }

    @Override
    public void initExtension() {
        onClick();
    }

    @Override
    public void onClick() {
        if (Boolean.getBoolean("java.awt.headless") || closed.get()) return;
        SwingUtilities.invokeLater(
                () -> {
                    synchronized (lifecycleLock) {
                        if (closed.get()) return;
                        if (window == null) {
                            window =
                                    new FactsWindow(
                                            settings,
                                            this::saveSettings,
                                            this::startPublishing,
                                            scheduler::stop,
                                            this::savePrefix);
                        }
                        window.show(latest);
                    }
                });
    }

    private void startPublishing() {
        synchronized (schedulerTransitionLock) {
            final long roomSnapshot;
            final boolean connected;
            synchronized (connectionLock) {
                connected = origins;
                roomSnapshot = roomId;
            }
            if (!connected) {
                publishStatus("Connect to Habbo Origins through G-Earth first");
                return;
            }
            if (roomSnapshot <= 0) {
                publishStatus("Enter a room before starting");
                return;
            }
            if (settings.apiKey().isBlank()) {
                publishStatus("Save an API Ninjas key before starting");
                return;
            }
            try {
                if (!scheduler.start(settings.apiKey(), settings.prefix(), roomSnapshot))
                    publishStatus("Room changed before publishing could start");
            } catch (IllegalArgumentException e) {
                publishStatus("Prefix cannot be published: " + e.getMessage());
            }
        }
    }

    private void savePrefix(String prefix) {
        Settings updated = new Settings(settings.apiKey(), prefix);
        settings = updated;
        scheduler.setPrefix(prefix);
        try {
            settingsStore.save(updated);
        } catch (IOException e) {
            publishStatus("Could not save the prefix; check local profile permissions");
        }
    }

    private void saveSettings(Settings updated) {
        try {
            settingsStore.save(updated);
            settings = updated;
            scheduler.setPrefix(updated.prefix());
            publishStatus("Settings saved");
        } catch (IOException e) {
            publishStatus("Could not save settings; publishing was not changed");
        }
    }

    private void publishStatus(String status) {
        synchronized (snapshotLock) {
            PublisherSnapshot old = latest;
            publishLocked(
                    new PublisherSnapshot(
                            old.running(),
                            roomId,
                            old.nextAt(),
                            old.lastFact(),
                            old.part(),
                            old.parts(),
                            status));
        }
    }

    private void publish(PublisherSnapshot value) {
        synchronized (snapshotLock) {
            publishLocked(value);
        }
    }

    private void publishLocked(PublisherSnapshot value) {
        latest = value;
        FactsWindow current = window;
        if (current != null && !closed.get())
            SwingUtilities.invokeLater(
                    () -> {
                        if (window == current && isLatestSnapshot(latest, value))
                            current.update(value);
                    });
    }

    static boolean isLatestSnapshot(PublisherSnapshot latest, PublisherSnapshot value) {
        return latest == value;
    }

    @Override
    public void run() {
        try {
            super.run();
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        FactsWindow current;
        synchronized (lifecycleLock) {
            synchronized (connectionLock) {
                if (!closed.compareAndSet(false, true)) return;
                origins = false;
                roomId = 0;
            }
            current = window;
        }
        synchronized (schedulerTransitionLock) {
            scheduler.close();
        }
        try {
            settingsStore.close();
        } catch (IOException ignored) {
        }
        if (current != null) SwingUtilities.invokeLater(current::dispose);
    }

    public static void main(String[] args) throws Exception {
        try (FactExtension extension = new FactExtension(args)) {
            extension.run();
        }
    }
}
