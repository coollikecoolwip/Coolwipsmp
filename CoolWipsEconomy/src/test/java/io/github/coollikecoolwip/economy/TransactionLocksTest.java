package io.github.coollikecoolwip.economy;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

class TransactionLocksTest {
    @Test void anotherCommandOnServerThreadCannotReenterPendingTransaction() {
        ReentrantLock lock = new ReentrantLock();
        assertTrue(TransactionLocks.tryAcquire(lock));
        assertFalse(TransactionLocks.tryAcquire(lock));
        assertEquals(1, lock.getHoldCount());
        lock.unlock();
        assertTrue(TransactionLocks.tryAcquire(lock));
        lock.unlock();
    }

    @Test void backgroundBalanceGuardCannotEnterPendingTransaction() {
        ReentrantLock lock = new ReentrantLock();
        assertTrue(TransactionLocks.tryAcquire(lock));
        assertFalse(CompletableFuture.supplyAsync(() -> TransactionLocks.tryAcquire(lock)).join());
        lock.unlock();
    }
}
