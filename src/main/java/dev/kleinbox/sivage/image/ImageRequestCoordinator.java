package dev.kleinbox.sivage.image;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded worker pool for network and WASM work. World state is never touched here. */
public final class ImageRequestCoordinator {
    private static final int MAX_QUEUE_SIZE = 32;

    private final ConcurrentHashMap<UUID, Future<?>> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    public ImageRequestCoordinator(int workers) {
        int boundedWorkers = Math.clamp(workers, 1, 16);
        executor = new ThreadPoolExecutor(
                boundedWorkers,
                boundedWorkers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE_SIZE),
                runnable -> {
                    Thread thread = new Thread(runnable, "sivage-image-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public boolean isActive(UUID playerId) {
        return active.containsKey(playerId);
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * Starts work for a player. The reservation remains active until {@link #finish(UUID)} is called
     * on the server thread, even after background processing completes.
     */
    public boolean submit(UUID playerId, Runnable work) {
        FutureTask<Void> task = new FutureTask<>(work, null);
        if (active.putIfAbsent(playerId, task) != null)
            return false;

        try {
            executor.execute(task);
            return true;
        } catch (RuntimeException e) {
            active.remove(playerId, task);
            task.cancel(false);
            return false;
        }
    }

    public void finish(UUID playerId) {
        active.remove(playerId);
    }

    public void cancel(UUID playerId) {
        Future<?> task = active.remove(playerId);
        if (task != null)
            task.cancel(true);
    }

    public void shutdown() {
        active.values().forEach(task -> task.cancel(true));
        active.clear();
        executor.shutdownNow();
    }
}
