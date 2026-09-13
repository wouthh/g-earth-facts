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
    private volatile Settings settings;
    private volatile boolean origins;
    private volatile long roomId;
    private volatile FactsWindow window;
    private volatile PublisherSnapshot latest = PublisherSnapshot.stopped("Waiting for Origins");

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
                    // A reconnect is a new publishing generation.  Requiring a fresh Start
                    // avoids carrying a request or countdown across connection boundaries.
                    scheduler.disconnect();
                    origins = OriginsProtocol.isOrigins(host, client);
                    roomId = 0;
                    publish(
                            new PublisherSnapshot(
                                    false,
                                    0,
                                    null,
                                    latest.lastFact(),
                                    0,
                                    0,
                                    origins
                                            ? "Connected; waiting for a room"
                                            : "Unsupported client; Origins/Shockwave is required"));
                });
        intercept(HMessage.Direction.TOCLIENT, OriginsProtocol.ROOM_READY_HEADER, this::roomReady);
        intercept(HMessage.Direction.TOSERVER, OriginsProtocol.QUIT_HEADER, this::navigation);
        intercept(HMessage.Direction.TOSERVER, OriginsProtocol.GOTO_FLAT_HEADER, this::navigation);
    }

    private boolean sendShout(HPacket packet) {
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

    private void roomReady(HMessage message) {
        if (!origins) return;
        if (message.isBlocked()) {
            roomId = 0;
            scheduler.roomChanged(0);
            return;
        }
        OptionalLong parsed = OriginsProtocol.roomId(message.getPacket());
        if (parsed.isEmpty()) {
            roomId = 0;
            scheduler.roomChanged(0);
            publishStatus("Malformed room context; publishing paused");
            return;
        }
        roomId = parsed.getAsLong();
        scheduler.roomChanged(roomId);
        publishStatus("Room " + roomId + " is ready");
    }

    private void navigation(HMessage message) {
        if (!origins || message.isBlocked()) return;
        roomId = 0;
        scheduler.roomChanged(0);
        publishStatus("Leaving room; waiting for the next room");
    }

    @Override
    public void onEndConnection() {
        origins = false;
        roomId = 0;
        scheduler.disconnect();
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
                });
    }

    private void startPublishing() {
        if (!origins) {
            publishStatus("Connect to Habbo Origins through G-Earth first");
            return;
        }
        if (roomId <= 0) {
            publishStatus("Enter a room before starting");
            return;
        }
        if (settings.apiKey().isBlank()) {
            publishStatus("Save an API Ninjas key before starting");
            return;
        }
        scheduler.start(settings.apiKey(), settings.prefix(), roomId);
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
        PublisherSnapshot old = latest;
        publish(
                new PublisherSnapshot(
                        old.running(),
                        roomId,
                        old.nextAt(),
                        old.lastFact(),
                        old.part(),
                        old.parts(),
                        status));
    }

    private void publish(PublisherSnapshot value) {
        latest = value;
        FactsWindow current = window;
        if (current != null && !closed.get())
            SwingUtilities.invokeLater(
                    () -> {
                        if (window == current) current.update(value);
                    });
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
        if (!closed.compareAndSet(false, true)) return;
        scheduler.close();
        try {
            settingsStore.close();
        } catch (IOException ignored) {
        }
        FactsWindow current = window;
        if (current != null) SwingUtilities.invokeLater(current::dispose);
    }

    public static void main(String[] args) throws Exception {
        try (FactExtension extension = new FactExtension(args)) {
            extension.run();
        }
    }
}
