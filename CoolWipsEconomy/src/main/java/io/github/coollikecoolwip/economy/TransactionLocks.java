package io.github.coollikecoolwip.economy;

import java.util.concurrent.locks.ReentrantLock;

final class TransactionLocks {
    private TransactionLocks() {}

    static boolean tryAcquire(ReentrantLock lock) {
        // Economy locks stay held while HTTP work runs on a worker. All player
        // commands enter on the same server thread, so reentry must be refused.
        return !lock.isHeldByCurrentThread() && lock.tryLock();
    }
}
