package com.potg.don.demo.admission;

import static com.potg.don.demo.admission.DemoAdmissionException.Code.DEMO_ADMISSION_BUSY;
import static com.potg.don.demo.admission.DemoAdmissionException.Code.DEMO_ADMISSION_UNAVAILABLE;
import static com.potg.don.demo.admission.DemoAdmissionException.Code.DEMO_CAPACITY_FULL;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.potg.don.demo.seed.DemoSeedScenario;

@Repository
@Profile("demo")
public class DemoAdmissionStore {
    private final JdbcTemplate jdbc;
    private final int expectedMax;

    public DemoAdmissionStore(JdbcTemplate jdbc, @Value("${app.demo.max-visitors:1000}") int expectedMax) {
        this.jdbc = jdbc;
        this.expectedMax = expectedMax;
        if (expectedMax < 1) throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
    }

    public long claimSlot() {
        requireBoundTransaction();
        if (currentClaim() != null) throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                DemoAdmissionSchema.verifyTransactionMode(connection);
                return null;
            });
            // Only this exact lock acquisition can produce a public BUSY response.
            try {
                if (jdbc.queryForList("SELECT id FROM demo_admission_lock WHERE id=1 FOR UPDATE NOWAIT").size() != 1) {
                    throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
                }
            } catch (DataAccessException failure) {
                if (DemoAdmissionSchema.isNowaitConflict(failure)) throw new DemoAdmissionException(DEMO_ADMISSION_BUSY);
                throw failure;
            }
            DemoAdmissionSchema.Capacity capacity = snapshot();
            // The marker table is the only occupancy source; configuration is read-only.
            if (capacity.visitCount() >= capacity.maxVisitors()) {
                throw new DemoAdmissionException(DEMO_CAPACITY_FULL);
            }
            ConnectionHolder holder = boundHolder();
            TransactionSynchronizationManager.registerSynchronization(new Claim(this, holder,
                holder.getConnection(), capacity.visitCount()));
            return capacity.visitCount();
        } catch (DataAccessException failure) {
            throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
        }
    }

    public void recordVisit(long userId, Instant expiresAt) {
        Claim claim = requireClaimedTransaction();
        if (claim.recorded || userId <= 0 || expiresAt == null) throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
        try {
            int changed = jdbc.update("INSERT INTO demo_visit "
                + "(user_id, created_at, scenario_version, session_expires_at) VALUES (?, UTC_TIMESTAMP(6), ?, ?)",
                userId, DemoSeedScenario.VERSION, LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            if (changed != 1) throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
            claim.recorded = true;
        } catch (DataAccessException failure) {
            throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
        }
    }

    public void verifyIntegrity(long initialCount) {
        Claim claim = requireClaimedTransaction();
        if (!claim.recorded || claim.verified || initialCount != claim.initialCount
            || initialCount < 0 || initialCount >= expectedMax) {
            throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
        }
        try {
            if (snapshot().visitCount() != initialCount + 1) {
                throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
            }
            claim.verified = true;
        }
        catch (DataAccessException failure) { throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE); }
    }

    private DemoAdmissionSchema.Capacity snapshot() {
        return jdbc.execute((ConnectionCallback<DemoAdmissionSchema.Capacity>) connection ->
            DemoAdmissionSchema.verifySnapshot(connection, expectedMax));
    }

    private void requireBoundTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            || jdbc.getDataSource() == null
            || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
        }
    }

    private ConnectionHolder boundHolder() {
        Object bound = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        if (!(bound instanceof ConnectionHolder holder)) throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
        return holder;
    }

    private Claim currentClaim() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Claim claim && claim.owner == this && claim.active) return claim;
        }
        return null;
    }

    private Claim requireClaimedTransaction() {
        requireBoundTransaction();
        Claim claim = currentClaim();
        ConnectionHolder holder = boundHolder();
        if (claim == null || claim.holder != holder || claim.connection != holder.getConnection()) {
            throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
        }
        return claim;
    }

    /** Proof of a lock in this transaction, not occupancy state or a JVM lock.
     * Spring suspends/restores synchronizations with REQUIRES_NEW and clears them at completion. */
    private static final class Claim implements TransactionSynchronization {
        final DemoAdmissionStore owner;
        final ConnectionHolder holder;
        final Connection connection;
        final long initialCount;
        boolean active = true;
        boolean recorded;
        boolean verified;

        Claim(DemoAdmissionStore owner, ConnectionHolder holder, Connection connection, long initialCount) {
            this.owner = owner;
            this.holder = holder;
            this.connection = connection;
            this.initialCount = initialCount;
        }

        @Override public void beforeCommit(boolean readOnly) {
            if (recorded && (readOnly || owner.requireClaimedTransaction() != this || !verified)) {
                throw new DemoAdmissionException(DEMO_ADMISSION_UNAVAILABLE);
            }
        }

        @Override public void afterCompletion(int status) { active = false; }
    }

}
