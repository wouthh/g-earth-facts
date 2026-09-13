package io.github.wouthh.gearthfacts;

import static org.junit.jupiter.api.Assertions.*;

import io.github.wouthh.gearthfacts.protocol.ShoutComposer;
import io.github.wouthh.gearthfacts.runtime.FactFailure;
import io.github.wouthh.gearthfacts.runtime.FactScheduler;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FactSchedulerTest {
    @Test
    void firstFactWaitsAndRoomChangeCancelsOldGeneration() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch fetchCalled = new CountDownLatch(1);
            CountDownLatch sent = new CountDownLatch(1);
            AtomicInteger requests = new AtomicInteger();
            var packets = new CopyOnWriteArrayList<gearth.protocol.HPacket>();
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> {
                                requests.incrementAndGet();
                                fetchCalled.countDown();
                                return CompletableFuture.completedFuture("fact");
                            },
                            packet -> {
                                packets.add(packet);
                                sent.countDown();
                                return true;
                            },
                            executor,
                            Duration.ofMillis(80),
                            Duration.ofMillis(10),
                            ignored -> {});
            scheduler.roomChanged(1);
            scheduler.start("key", "", 1);
            scheduler.start("key", "ignored", 1);
            assertFalse(fetchCalled.await(20, TimeUnit.MILLISECONDS));
            scheduler.roomChanged(2);
            assertFalse(sent.await(55, TimeUnit.MILLISECONDS));
            assertTrue(sent.await(200, TimeUnit.MILLISECONDS));
            assertEquals("fact", ShoutComposer.decode(packets.getFirst()));
            assertEquals(1, requests.get());
            scheduler.close();
        } finally {
            executor.shutdownNow();
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
            FactScheduler stopped =
                    new FactScheduler(
                            key -> auth,
                            packet -> true,
                            executor,
                            Duration.ofMillis(10),
                            Duration.ofMillis(10),
                            ignored -> {});
            stopped.roomChanged(1);
            stopped.start("key", "", 1);
            auth.completeExceptionally(
                    new FactFailure(FactFailure.Kind.AUTHENTICATION, "rejected"));
            Thread.sleep(40);
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
}
