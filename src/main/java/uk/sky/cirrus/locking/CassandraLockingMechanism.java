package uk.sky.cirrus.locking;

import com.datastax.driver.core.*;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.WriteTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.sky.cirrus.locking.exception.CannotAcquireLockException;
import uk.sky.cirrus.locking.exception.CannotReleaseLockException;

import java.util.UUID;

public class CassandraLockingMechanism implements LockingMechanism {

    private static final Logger log = LoggerFactory.getLogger(CassandraLockingMechanism.class);

    private final Session session;
    private final String lockName;
    private final UUID clientId;

    private boolean isRetryAfterWriteTimeout = false;

    public CassandraLockingMechanism(Session session, String keyspace, UUID clientId) {
        this.session = session;
        this.lockName = keyspace + ".schema_migration";
        this.clientId = clientId;
    }

    @Override
    public boolean acquire() {
        Statement query = new SimpleStatement("INSERT INTO locks.locks (name, client) VALUES (?, ?) IF NOT EXISTS", lockName, clientId);

        try {
            ResultSet resultSet = session.execute(query);
            Row currentLock = resultSet.one();
            if (currentLock.getBool("[applied]") || clientId.equals(currentLock.getUUID("client"))) {
                return true;
            } else {
                log.info("Lock currently held by {}", currentLock);
                return false;
            }
        } catch (DriverException de) {
            log.warn("Query to acquire lock for {} failed to execute", clientId, de);
            throw new CannotAcquireLockException("Query failed to execute", de);
        }
    }

    @Override
    public boolean release() {
        Statement query = new SimpleStatement("DELETE FROM locks.locks WHERE name = ? IF client = ?", lockName, clientId);

        try {
            ResultSet resultSet = session.execute(query);
            Row result = resultSet.one();

            boolean isApplied = result.getBool("[applied]");

            if (isApplied || !result.getColumnDefinitions().contains("client")) {
                log.info("Lock released for {} by client {} at: {}", lockName, clientId, System.currentTimeMillis());
                return true;
            }

            UUID clientReleasingLock = result.getUUID("client");
            if (!clientReleasingLock.equals(clientId)) {
                if (isRetryAfterWriteTimeout) {
                    log.info("Released lock for client {} in retry attempt after WriteTimeoutException", clientReleasingLock);
                    return true;
                } else {
                    log.error("Lock attempted to be released by a non lock holder {}", clientReleasingLock);
                    throw new IllegalStateException("Lock attempted to be released by a non lock holder: " + clientReleasingLock);
                }
            }

        } catch (WriteTimeoutException e) {
            isRetryAfterWriteTimeout = true;
            waitToRetryRelease();

        } catch (DriverException e) {
            log.error("Query to release lock failed to execute for {} by client {}", lockName, clientId, e);
            throw new CannotReleaseLockException("Query failed to execute", e);
        }

        return false;
    }

    @Override
    public String getLockName() {
        return lockName;
    }

    private void waitToRetryRelease() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            log.warn("Thread sleep interrupted with {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
