package io.github.dglearner.shop.common;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Re-runs a transaction that MySQL rolled back to break a deadlock (error 1213). InnoDB picks a victim and undoes
 * all of its work, so running the whole transaction again is safe and is what the MySQL manual recommends.
 */
public final class DeadlockRetry {

    private static final Logger log = LoggerFactory.getLogger(DeadlockRetry.class);

    private static final int MYSQL_DEADLOCK = 1213;
    private static final int MAX_ATTEMPTS = 3;

    public static <T> T run(String operation, Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (DataAccessException e) {
                if (attempt >= MAX_ATTEMPTS || !isDeadlock(e)) {
                    throw e;
                }
                log.atWarn().addKeyValue("operation", operation).addKeyValue("attempt", attempt)
                        .log("Transaction chosen as deadlock victim, retrying");
            }
        }
    }

    static boolean isDeadlock(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == MYSQL_DEADLOCK) {
                return true;
            }
        }
        return false;
    }

    private DeadlockRetry() {
    }
}
