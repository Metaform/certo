package org.metaform.certo.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Helpers for sequencing work relative to the current transaction.
 */
public final class TransactionSupport {

    private TransactionSupport() {
    }

    /**
     * Runs {@code action} <b>after</b> the current transaction commits — so a side effect that must not hold a
     * database connection (e.g. an outbound HTTP notification) runs once the state change is durably committed
     * and the connection has been released. If no transaction is active, the action runs immediately.
     * <p>
     * Note the actions are not guaranteed to complete and therefore cannot be used for guaranteed delivery.
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
