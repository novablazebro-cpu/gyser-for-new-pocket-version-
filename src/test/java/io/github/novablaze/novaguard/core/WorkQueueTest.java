package io.github.novablaze.novaguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkQueueTest {

    @Test
    void stopsWhenTheBudgetIsUsedAndContinuesNextTick() {
        AtomicLong now = new AtomicLong();
        WorkQueue queue = new WorkQueue(2_000_000, now::get);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            // Every task "takes" 1 ms.
            queue.add(() -> {
                ran.incrementAndGet();
                now.addAndGet(1_000_000);
            });
        }
        assertEquals(2, queue.runTick());
        assertEquals(8, queue.size());
        assertEquals(2, queue.runTick());
        assertEquals(4, ran.get());
    }

    @Test
    void alwaysRunsAtLeastOneTaskSoItNeverStalls() {
        AtomicLong now = new AtomicLong();
        WorkQueue queue = new WorkQueue(1, now::get);
        queue.add(() -> now.addAndGet(50_000_000));
        queue.add(() -> { });
        assertEquals(1, queue.runTick());
        assertEquals(1, queue.runTick());
        assertTrue(queue.isEmpty());
    }

    @Test
    void emptyQueueDoesNothing() {
        assertEquals(0, new WorkQueue(1_000_000).runTick());
    }
}
