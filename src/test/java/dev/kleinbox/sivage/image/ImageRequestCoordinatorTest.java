package dev.kleinbox.sivage.image;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ImageRequestCoordinatorTest {
    @Test
    void permitsOnlyOneActiveRequestPerPlayer() throws Exception {
        ImageRequestCoordinator coordinator = new ImageRequestCoordinator(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UUID player = UUID.randomUUID();
        try {
            assertTrue(coordinator.submit(player, () -> await(started, release)));
            started.await();
            assertTrue(coordinator.isActive(player));
            assertFalse(coordinator.submit(player, () -> {}));

            release.countDown();
            coordinator.finish(player);
            assertFalse(coordinator.isActive(player));
        } finally {
            release.countDown();
            coordinator.shutdown();
        }
    }

    @Test
    void rejectsWorkBeyondBoundedQueue() throws Exception {
        ImageRequestCoordinator coordinator = new ImageRequestCoordinator(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<UUID> accepted = new ArrayList<>();
        try {
            UUID running = UUID.randomUUID();
            assertTrue(coordinator.submit(running, () -> await(started, release)));
            accepted.add(running);
            started.await();

            for (int i = 0; i < 32; i++) {
                UUID queued = UUID.randomUUID();
                assertTrue(coordinator.submit(queued, () -> {}));
                accepted.add(queued);
            }
            assertFalse(coordinator.submit(UUID.randomUUID(), () -> {}));
        } finally {
            release.countDown();
            accepted.forEach(coordinator::finish);
            coordinator.shutdown();
        }
    }

    @Test
    void runsFourWorkersConcurrentlyAndCleansUpCancellationAndShutdown() throws Exception {
        ImageRequestCoordinator coordinator = new ImageRequestCoordinator(4);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        List<UUID> players = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                UUID player = UUID.randomUUID();
                players.add(player);
                assertTrue(coordinator.submit(player, () -> await(started, release)));
            }
            assertTrue(started.await(2, TimeUnit.SECONDS), "all four workers should start without serializing");

            coordinator.cancel(players.getFirst());
            assertFalse(coordinator.isActive(players.getFirst()));

            coordinator.shutdown();
            assertTrue(coordinator.isShutdown());
            players.forEach(player -> assertFalse(coordinator.isActive(player)));
        } finally {
            release.countDown();
            if (!coordinator.isShutdown())
                coordinator.shutdown();
        }
    }

    private static void await(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
