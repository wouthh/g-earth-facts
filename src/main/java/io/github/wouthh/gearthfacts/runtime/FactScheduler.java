package io.github.wouthh.gearthfacts.runtime;

import gearth.protocol.HPacket;
import io.github.wouthh.gearthfacts.protocol.ShoutComposer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One cancellable, generation-bound publishing loop. */
public final class FactScheduler implements AutoCloseable {
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(10);
    public static final Duration DEFAULT_PART_DELAY = Duration.ofSeconds(5);

    @FunctionalInterface
    public interface ShoutSender {
        boolean send(HPacket packet);
    }

    private final FactClient client;
    private final ShoutSender sender;
    private final ScheduledExecutorService executor;
    private final Duration interval;
    private final Duration partDelay;
    private final Consumer<PublisherSnapshot> listener;
    private final Object lock = new Object();
    private boolean closed;
    private boolean running;
    private long generation;
    private long roomId;
    private String apiKey = "";
    private String prefix = "";
    private String lastFact = "";
    private List<HPacket> pendingParts = List.of();
    private int part;
    private CompletableFuture<String> inFlight;
    private ScheduledFuture<?> scheduled;
    private Instant nextAt;

    public FactScheduler(
            FactClient client,
            ShoutSender sender,
            ScheduledExecutorService executor,
            Duration interval,
            Duration partDelay,
            Consumer<PublisherSnapshot> listener) {
        this.client = Objects.requireNonNull(client);
        this.sender = Objects.requireNonNull(sender);
        this.executor = Objects.requireNonNull(executor);
        this.interval = requirePositive(interval, "interval");
        this.partDelay = requirePositive(partDelay, "partDelay");
        this.listener = listener == null ? ignored -> {} : listener;
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero())
            throw new IllegalArgumentException(name);
        return duration;
    }

    public void start(String apiKey, String prefix, long roomId) {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("An API key is required");
        if (roomId <= 0) throw new IllegalArgumentException("A known room is required");
        synchronized (lock) {
            ensureOpen();
            if (running) return;
            this.apiKey = apiKey.trim();
            this.prefix = prefix == null ? "" : prefix;
            this.roomId = roomId;
            running = true;
            generation++;
            cancelWorkLocked();
            scheduleTickLocked(generation, interval);
            publishLocked("Armed; first fact in 10 minutes");
        }
    }

    public void setPrefix(String prefix) {
        synchronized (lock) {
            if (!closed) this.prefix = prefix == null ? "" : prefix;
        }
    }

    public void roomChanged(long newRoomId) {
        synchronized (lock) {
            if (closed) return;
            boolean wasRunning = running;
            long normalizedRoom = newRoomId > 0 ? newRoomId : 0;
            if (this.roomId == normalizedRoom && (normalizedRoom > 0 || !wasRunning)) return;
            this.roomId = normalizedRoom;
            generation++;
            cancelWorkLocked();
            if (!wasRunning) {
                publishLocked(newRoomId > 0 ? "Stopped; room ready" : "Stopped; room unknown");
            } else if (newRoomId > 0) {
                scheduleTickLocked(generation, interval);
                publishLocked("Room changed; next fact in 10 minutes");
            } else {
                publishLocked("Waiting for a known room; publishing paused");
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            if (closed) return;
            running = false;
            generation++;
            cancelWorkLocked();
            publishLocked("Stopped");
        }
    }

    public void disconnect() {
        synchronized (lock) {
            if (closed) return;
            running = false;
            roomId = 0;
            generation++;
            cancelWorkLocked();
            publishLocked("Disconnected; publishing stopped");
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    private void scheduleTickLocked(long token, Duration delay) {
        if (!running || roomId <= 0 || closed) return;
        long millis = Math.max(1, delay.toMillis());
        nextAt = Instant.now().plusMillis(millis);
        scheduled = executor.schedule(() -> fetch(token), millis, TimeUnit.MILLISECONDS);
    }

    private void fetch(long token) {
        String key;
        String currentPrefix;
        synchronized (lock) {
            if (closed || !running || roomId <= 0 || token != generation) return;
            key = apiKey;
            currentPrefix = prefix;
            scheduled = null;
            nextAt = null;
            publishLocked("Fetching a fact");
            try {
                inFlight = client.fetch(key);
            } catch (RuntimeException e) {
                inFlight = null;
                handleFailureLocked(token, e);
                return;
            }
        }
        final String sequencePrefix = currentPrefix;
        CompletableFuture<String> future;
        synchronized (lock) {
            future = inFlight;
        }
        future.whenCompleteAsync(
                (fact, failure) -> factReady(token, sequencePrefix, fact, failure), executor);
    }

    private void factReady(long token, String sequencePrefix, String fact, Throwable failure) {
        synchronized (lock) {
            if (closed || !running || token != generation) return;
            inFlight = null;
            if (failure != null) {
                handleFailureLocked(token, unwrap(failure));
                return;
            }
            try {
                pendingParts = ShoutComposer.compose(sequencePrefix, fact);
            } catch (RuntimeException e) {
                handleFailureLocked(token, e);
                return;
            }
            lastFact = fact;
            part = 0;
            sendNextLocked(token);
        }
    }

    private void sendNextLocked(long token) {
        if (closed || !running || token != generation || roomId <= 0) return;
        if (part >= pendingParts.size()) {
            pendingParts = List.of();
            part = 0;
            scheduleTickLocked(token, interval);
            publishLocked("Published; next fact in 10 minutes");
            return;
        }
        HPacket packet = pendingParts.get(part);
        boolean sent;
        try {
            sent = sender.send(packet);
        } catch (RuntimeException e) {
            sent = false;
        }
        if (!sent) {
            pendingParts = List.of();
            part = 0;
            scheduleTickLocked(token, interval);
            publishLocked("G-Earth rejected the shout; next interval remains scheduled");
            return;
        }
        part++;
        publishLocked("Published part " + part + " of " + pendingParts.size());
        if (part < pendingParts.size()) {
            long millis = Math.max(1, partDelay.toMillis());
            nextAt = Instant.now().plusMillis(millis);
            scheduled =
                    executor.schedule(
                            () -> {
                                synchronized (lock) {
                                    if (token == generation) sendNextLocked(token);
                                }
                            },
                            millis,
                            TimeUnit.MILLISECONDS);
        } else {
            pendingParts = List.of();
            scheduleTickLocked(token, interval);
            publishLocked("Published; next fact in 10 minutes");
        }
    }

    private void handleFailureLocked(long token, Throwable failure) {
        String message =
                failure instanceof FactFailure ff ? ff.getMessage() : "Fact request failed";
        if (failure instanceof FactFailure ff && ff.kind() == FactFailure.Kind.AUTHENTICATION) {
            running = false;
            cancelWorkLocked();
            publishLocked("API key rejected; publishing stopped");
        } else {
            scheduleTickLocked(token, interval);
            publishLocked(
                    message == null
                            ? "Fact request failed; next interval remains scheduled"
                            : message);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException
                && throwable.getCause() != null) return throwable.getCause();
        return throwable;
    }

    private void cancelWorkLocked() {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
        if (inFlight != null) inFlight.cancel(true);
        inFlight = null;
        pendingParts = List.of();
        part = 0;
        nextAt = null;
    }

    private void publishLocked(String status) {
        PublisherSnapshot snapshot =
                new PublisherSnapshot(
                        running, roomId, nextAt, lastFact, part, pendingParts.size(), status);
        try {
            listener.accept(snapshot);
        } catch (RuntimeException ignored) {
            // UI listeners must not stop the scheduling owner.
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Scheduler is closed");
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            running = false;
            generation++;
            cancelWorkLocked();
        }
        executor.shutdownNow();
    }
}
