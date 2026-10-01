package com.potg.don.demo.admission;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class DemoAdmissionSqlErrorTest {
    @Test void storeCannotRunOutsideTheBoundLoginTransaction() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var store = new DemoAdmissionStore(jdbc, 2);
        for (Runnable operation : java.util.List.<Runnable>of(store::claimSlot, () -> store.verifyIntegrity(0L),
            () -> store.recordVisit(1L, java.time.Instant.now()))) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(DemoAdmissionException.class, operation::run);
            assertThat(failure.code()).isEqualTo(DemoAdmissionException.Code.DEMO_ADMISSION_UNAVAILABLE);
        }
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test void onlyExactVendorAndSqlStateAreBusy() {
        assertThat(DemoAdmissionSchema.isNowaitConflict(new SQLException("not inspected", "HY000", 3572))).isTrue();
        for (int code : new int[] {1205, 1213, 0, 1045, 1142, 1146, 2006, 8121}) {
            assertThat(DemoAdmissionSchema.isNowaitConflict(new SQLException("not inspected", "HY000", code))).isFalse();
        }
        assertThat(DemoAdmissionSchema.isNowaitConflict(new SQLException("not inspected", "42000", 1142))).isFalse();
        assertThat(DemoAdmissionSchema.isNowaitConflict(new SQLException("3572 NOWAIT", "08006", 3572))).isFalse();
        assertThat(DemoAdmissionSchema.isNowaitConflict(new IllegalStateException("NOWAIT lock"))).isFalse();
    }

    @Test void wrappedAndChainedExceptionsPreserveExactNowaitRecognition() {
        SQLException root = new SQLException("not inspected", "HY000", 0);
        root.setNextException(new SQLException("not inspected", "HY000", 3572));
        assertThat(DemoAdmissionSchema.isNowaitConflict(new IllegalStateException(root))).isTrue();
    }

    @Test void conflictingDeadlockOrConnectionFailureIsNeverReclassifiedAsBusy() {
        for (var other : new SQLException[] {new SQLException("not inspected", "40001", 1213),
            new SQLException("not inspected", "08006", 0), new SQLException("not inspected", "HY000", 1205)}) {
            other.setNextException(new SQLException("not inspected", "HY000", 3572));
            assertThat(DemoAdmissionSchema.isNowaitConflict(other)).isFalse();
        }
    }

    @Test void malformedCyclesTerminateWithoutInferringBusyFromMessages() {
        RuntimeException one = new RuntimeException("NOWAIT");
        RuntimeException two = new RuntimeException("3572");
        one.initCause(two);
        two.initCause(one);
        assertThat(DemoAdmissionSchema.isNowaitConflict(one)).isFalse();
    }
}
