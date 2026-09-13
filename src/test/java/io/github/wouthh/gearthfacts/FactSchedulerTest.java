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
import org.junit.jupiter.api.Test;

class FactSchedulerTest {
    @Test
    void firstFactWaitsAndRoomChangeCancelsOldGeneration() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch fetchCalled = new CountDownLatch(1);
            CountDownLatch sent = new CountDownLatch(1);
            var packets = new CopyOnWriteArrayList<gearth.protocol.HPacket>();
            FactScheduler scheduler =
                    new FactScheduler(
                            key -> {
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
            scheduler.start("key", "", 1);
            assertFalse(fetchCalled.await(20, TimeUnit.MILLISECONDS));
            scheduler.roomChanged(2);
            assertFalse(sent.await(55, TimeUnit.MILLISECONDS));
            assertTrue(sent.await(200, TimeUnit.MILLISECONDS));
            assertEquals("fact", ShoutComposer.decode(packets.getFirst()));
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
}
