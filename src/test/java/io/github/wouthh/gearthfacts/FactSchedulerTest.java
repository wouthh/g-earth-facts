package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.gearthfacts.protocol.ShoutComposer;
import io.github.wouthh.gearthfacts.runtime.FactFailure;
import io.github.wouthh.gearthfacts.runtime.FactScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FactSchedulerTest {
    @Test
    void firstFactWaitsAndRoomChangeCancelsOldGeneration() throws Exception {
        ManualScheduledExecutor executor = new ManualScheduledExecutor();
        try {
            AtomicInteger requests = new AtomicInteger();
            var packets = new CopyOnWriteArrayList<gearth.protocol.HPacket>();
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> {
                                requests.incrementAndGet();
                                return CompletableFuture.completedFuture("fact");
                            },
                            packet -> {
                                packets.add(packet);
                                return true;
                            },
                            executor,
                            Duration.ofMillis(80),
                            Duration.ofMillis(10),
                            ignored -> {});
            scheduler.roomChanged(1);
            assertTrue(scheduler.start("key", "", 1));
            assertTrue(scheduler.start("key", "ignored", 1));
            assertEquals(1, executor.activeTaskCount());
            scheduler.roomChanged(2);
            assertTrue(packets.isEmpty());
            assertEquals(1, executor.activeTaskCount());
            executor.runNextActive();
            assertEquals("fact", ShoutComposer.decode(packets.getFirst()));
            assertEquals(1, requests.get());
            scheduler.close();
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class ManualScheduledExecutor extends AbstractExecutorService
            implements ScheduledExecutorService {
        private final List<ManualTask> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override
        public synchronized ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            if (shutdown) throw new RejectedExecutionException();
            ManualTask task = new ManualTask(command);
            tasks.add(task);
            return task;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(
                java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        void runNextActive() {
            ManualTask next = null;
            synchronized (this) {
                for (var iterator = tasks.iterator(); iterator.hasNext(); ) {
                    ManualTask candidate = iterator.next();
                    if (candidate.isCancelled() || candidate.isDone()) {
                        iterator.remove();
                    } else {
                        iterator.remove();
                        next = candidate;
                        break;
                    }
                }
            }
            if (next == null) throw new AssertionError("no active scheduled task");
            next.runTask();
        }

        synchronized int activeTaskCount() {
            int count = 0;
            for (ManualTask task : tasks) if (!task.isCancelled() && !task.isDone()) count++;
            return count;
        }

        @Override
        public void execute(Runnable command) {
            synchronized (this) {
                if (shutdown) throw new RejectedExecutionException();
            }
            command.run();
        }

        @Override
        public synchronized void shutdown() {
            shutdown = true;
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>();
            for (ManualTask task : tasks) {
                if (!task.isDone()) {
                    pending.add(task.command);
                    task.cancel(false);
                }
            }
            tasks.clear();
            return pending;
        }

        @Override
        public synchronized boolean isShutdown() {
            return shutdown;
        }

        @Override
        public synchronized boolean isTerminated() {
            return shutdown && tasks.stream().allMatch(ManualTask::isDone);
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        private static final class ManualTask implements ScheduledFuture<Object> {
            private final Runnable command;
            private boolean cancelled;
            private boolean done;

            private ManualTask(Runnable command) {
                this.command = command;
            }

            private synchronized void runTask() {
                if (cancelled || done) return;
                try {
                    command.run();
                } finally {
                    done = true;
                }
            }

            @Override
            public synchronized boolean cancel(boolean mayInterruptIfRunning) {
                if (done) return false;
                cancelled = true;
                done = true;
                return true;
            }

            @Override
            public synchronized boolean isCancelled() {
                return cancelled;
            }

            @Override
            public synchronized boolean isDone() {
                return done;
            }

            @Override
            public Object get() throws InterruptedException, ExecutionException {
                synchronized (this) {
                    if (!done) throw new IllegalStateException("task has not been run");
                    if (cancelled) throw new java.util.concurrent.CancellationException();
                    return null;
                }
            }

            @Override
            public Object get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                return get();
            }

            @Override
            public long getDelay(TimeUnit unit) {
                return 0;
            }

            @Override
            public int compareTo(Delayed other) {
                return 0;
            }
        }
    }

    @Test
    void pendingFactKeepsPrefixSnapshotAndAuthFailureStops() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> response = new CompletableFuture<>();
            CountDownLatch requested = new CountDownLatch(1);
            CountDownLatch sent = new CountDownLatch(1);
            var packets = new CopyOnWriteArrayList<gearth.protocol.HPacket>();
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> {
                                requested.countDown();
                                return response;
                            },
                            packet -> {
                                packets.add(packet);
                                sent.countDown();
                                return true;
                            },
                            executor,
                            Duration.ofMillis(10),
                            Duration.ofMillis(10),
                            ignored -> {});
            scheduler.roomChanged(1);
            scheduler.start("key", "A", 1);
            assertTrue(requested.await(200, TimeUnit.MILLISECONDS));
            scheduler.setPrefix("B");
            response.complete("fact");
            assertTrue(sent.await(200, TimeUnit.MILLISECONDS));
            assertEquals("Afact", ShoutComposer.decode(packets.getFirst()));
            scheduler.stop();

            CompletableFuture<String> auth = new CompletableFuture<>();
            CountDownLatch authStopped = new CountDownLatch(1);
            FactScheduler stopped =
                    new FactScheduler(
                            key -> auth,
                            packet -> true,
                            executor,
                            Duration.ofMillis(10),
                            Duration.ofMillis(10),
                            snapshot -> {
                                if (!snapshot.running()
                                        && snapshot.status()
                                                .equals("API key rejected; publishing stopped"))
                                    authStopped.countDown();
                            });
            stopped.roomChanged(1);
            stopped.start("key", "", 1);
            auth.completeExceptionally(
                    new FactFailure(FactFailure.Kind.AUTHENTICATION, "rejected"));
            assertTrue(authStopped.await(1, TimeUnit.SECONDS));
            assertFalse(stopped.isRunning());
            stopped.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void disconnectCancelsAnInFlightRequestAndRemainingParts() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> response = new CompletableFuture<>();
            CountDownLatch requested = new CountDownLatch(1);
            var packets = new CopyOnWriteArrayList<gearth.protocol.HPacket>();
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> {
                                requested.countDown();
                                return response;
                            },
                            packet -> {
                                packets.add(packet);
                                return true;
                            },
                            executor,
                            Duration.ofMillis(5),
                            Duration.ofMillis(5),
                            ignored -> {});
            scheduler.roomChanged(1);
            scheduler.start("key", "", 1);
            assertTrue(requested.await(200, TimeUnit.MILLISECONDS));
            scheduler.disconnect();
            response.complete("x ".repeat(120));
            Thread.sleep(40);
            assertFalse(scheduler.isRunning());
            assertTrue(packets.isEmpty());
            scheduler.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startRejectsAStaleRoomSnapshotAfterDisconnect() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> CompletableFuture.completedFuture("fact"),
                            packet -> true,
                            executor,
                            Duration.ofSeconds(1),
                            Duration.ofMillis(10),
                            ignored -> {});
            scheduler.roomChanged(1);
            scheduler.disconnect();
            assertFalse(scheduler.start("key", "", 1));
            assertFalse(scheduler.isRunning());
            scheduler.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startRejectsAnUnpublishablePrefixBeforeArming() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> CompletableFuture.completedFuture("fact"),
                            packet -> true,
                            executor,
                            Duration.ofSeconds(1),
                            Duration.ofMillis(10),
                            ignored -> {});
            scheduler.roomChanged(1);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scheduler.start("key", "x".repeat(ShoutComposer.MAX_TOTAL_BYTES), 1));
            assertFalse(scheduler.isRunning());
            scheduler.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void permanentFactFailureStopsWithoutSchedulingAnotherRequest() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger requests = new AtomicInteger();
            CountDownLatch stopped = new CountDownLatch(1);
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> {
                                requests.incrementAndGet();
                                return CompletableFuture.failedFuture(
                                        new FactFailure(
                                                FactFailure.Kind.PERMANENT, "Invalid endpoint"));
                            },
                            packet -> true,
                            executor,
                            Duration.ofMillis(10),
                            Duration.ofMillis(10),
                            snapshot -> {
                                if (!snapshot.running()
                                        && snapshot.status().equals("Invalid endpoint"))
                                    stopped.countDown();
                            });
            scheduler.roomChanged(1);
            scheduler.start("key", "", 1);
            assertTrue(stopped.await(500, TimeUnit.MILLISECONDS));
            assertFalse(scheduler.isRunning());
            assertEquals(1, requests.get());
            scheduler.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidPrefixEditStopsAnActiveSequence() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<String> response = new CompletableFuture<>();
            CountDownLatch requested = new CountDownLatch(1);
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> {
                                requested.countDown();
                                return response;
                            },
                            packet -> true,
                            executor,
                            Duration.ofMillis(10),
                            Duration.ofMillis(10),
                            ignored -> {});
            scheduler.roomChanged(1);
            scheduler.start("key", "", 1);
            assertTrue(requested.await(500, TimeUnit.MILLISECONDS));
            scheduler.setPrefix("😀");
            assertFalse(scheduler.isRunning());
            assertTrue(response.isCancelled());
            scheduler.close();
        } finally {
            executor.shutdownNow();
        }
    }
}
