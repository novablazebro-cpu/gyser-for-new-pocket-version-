package io.github.novablaze.novaguard.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Bulk work spread over ticks: every tick, tasks run until the time budget is used up (at least one task runs, so
 * the queue always moves). Big jobs such as a world cleanup therefore never freeze the server.
 */
public final class WorkQueue implements Runnable {

    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private final long budgetNanos;
    private final LongSupplier clock;

    public WorkQueue(long budgetNanos) {
        this(budgetNanos, System::nanoTime);
    }

    /** @param clock nanosecond clock (replaceable in tests) */
    public WorkQueue(long budgetNanos, LongSupplier clock) {
        this.budgetNanos = budgetNanos;
        this.clock = clock;
    }

    public void add(Runnable task) {
        tasks.addLast(task);
    }

    public int size() {
        return tasks.size();
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    public void clear() {
        tasks.clear();
    }

    @Override
    public void run() {
        runTick();
    }

    /** One tick of work. @return how many tasks ran */
    public int runTick() {
        long start = clock.getAsLong();
        int done = 0;
        while (!tasks.isEmpty() && (done == 0 || clock.getAsLong() - start < budgetNanos)) {
            tasks.pollFirst().run();
            done++;
        }
        return done;
    }
}
