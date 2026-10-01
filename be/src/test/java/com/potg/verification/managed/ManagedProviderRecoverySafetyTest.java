package com.potg.verification.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** No providers or private task state. Synthetic cumulative budget and exact-account metadata only. */
class ManagedProviderRecoverySafetyTest {
    @Test void approvedLimitIsExplicitAndLegacyLimitIsUnchanged() {
        assertThat(ManagedProviderProbe.verificationBudgetLimit(Map.of("mode","managed"))).isEqualTo(10000);
        assertThat(ManagedProviderProbe.verificationBudgetLimit(Map.of("mode","local-rehearsal","verificationBudgetLimit","10000"))).isEqualTo(10000);
        assertThat(ManagedProviderProbe.verificationBudgetLimit(Map.of("mode","managed","verificationBudgetLimit","15568"))).isEqualTo(15568);
        assertThatThrownBy(()->ManagedProviderProbe.verificationBudgetLimit(Map.of("mode","managed","verificationBudgetLimit","15569"))).hasMessage("APPROVED_BUDGET_LIMIT");
        assertThatThrownBy(()->ManagedProviderProbe.verificationBudgetLimit(Map.of("mode","local-rehearsal","verificationBudgetLimit","15568"))).hasMessage("EXTENDED_BUDGET_MANAGED_ONLY");
    }
    @Test void recoveryRequiresExactUnmodifiedExistingBaseline() throws Exception {
        var valid=ManagedProviderProbe.JSON.readTree("{\"loginAttempts\":1,\"commandEquivalents\":9472}");
        var before=valid.deepCopy();
        ManagedProviderProbe.requireApprovedStartingBudget(15568,valid);
        assertThat(valid).isEqualTo(before);
        for (int used : new int[]{0,9471,9473,15568})
            assertThatThrownBy(()->ManagedProviderProbe.requireApprovedStartingBudget(15568,ManagedProviderProbe.JSON.valueToTree(Map.of("loginAttempts",1,"commandEquivalents",used)))).hasMessage("APPROVED_RECOVERY_BASELINE");
        for (int logins : new int[]{0,2})
            assertThatThrownBy(()->ManagedProviderProbe.requireApprovedStartingBudget(15568,ManagedProviderProbe.JSON.valueToTree(Map.of("loginAttempts",logins,"commandEquivalents",9472)))).hasMessage("APPROVED_RECOVERY_BASELINE");
    }
    @Test void fractionalMissingStringExtraAndNegativeBudgetNeverCoerceToApproval() throws Exception {
        for(String json : new String[]{"{}","{\"loginAttempts\":1,\"commandEquivalents\":9472.0}","{\"loginAttempts\":\"1\",\"commandEquivalents\":9472}","{\"loginAttempts\":1,\"commandEquivalents\":9472,\"extra\":0}","{\"loginAttempts\":-1,\"commandEquivalents\":9472}"}) {
            var node=ManagedProviderProbe.JSON.readTree(json);
            assertThatThrownBy(()->ManagedProviderProbe.requireApprovedStartingBudget(15568,node)).hasMessage("BUDGET_FORMAT");
        }
    }
    @Test void incrementalReservationsCannotExceedApprovedCeilingOrLoginLimit() {
        ManagedProviderProbe.requireReservation(15568,5,15504,64,false);
        ManagedProviderProbe.requireReservation(10000,1,9936,64,false);
        assertThatThrownBy(()->ManagedProviderProbe.requireReservation(15568,5,15568,64,false)).hasMessage("GLOBAL_BUDGET_LIMIT");
        assertThatThrownBy(()->ManagedProviderProbe.requireReservation(10000,1,10000,64,false)).hasMessage("GLOBAL_BUDGET_LIMIT");
        assertThatThrownBy(()->ManagedProviderProbe.requireReservation(15568,8,9472,64,true)).hasMessage("GLOBAL_BUDGET_LIMIT");
        assertThatThrownBy(()->ManagedProviderProbe.requireReservation(15568,1,Integer.MAX_VALUE,64,false)).hasMessage("GLOBAL_BUDGET_LIMIT");
        assertThatThrownBy(()->ManagedProviderProbe.requireReservation(15568,1,9472,-64,false)).hasMessage("GLOBAL_BUDGET_LIMIT");
    }
    @Test void exactAccountAbsentRequiresKnownMissingGrantError() throws Exception {
        Connection connection=mock(Connection.class); Statement statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW GRANTS FOR 'fixture.m123'@'%'")).thenThrow(new SQLException("not disclosed","42000",1141));
        assertThat(ManagedProviderProbe.ownedAccountAbsent(connection,"fixture.m123")).isTrue();
        verify(statement).executeQuery("SHOW GRANTS FOR 'fixture.m123'@'%'"); verify(statement).close();
    }
    @Test void existingAccountAndUnknownPermissionFailureAreNotAbsence() throws Exception {
        Connection connection=mock(Connection.class); Statement statement=mock(Statement.class); ResultSet rows=mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW GRANTS FOR 'fixture.m123'@'%'")).thenReturn(rows);
        assertThat(ManagedProviderProbe.ownedAccountAbsent(connection,"fixture.m123")).isFalse(); verify(rows).close();
        when(statement.executeQuery("SHOW GRANTS FOR 'fixture.m123'@'%'")).thenThrow(new SQLException("not disclosed","42000",1142));
        assertThatThrownBy(()->ManagedProviderProbe.ownedAccountAbsent(connection,"fixture.m123")).isInstanceOf(SQLException.class);
    }
    @Test void unsafeAccountIdentifierCannotProduceAnyQuery() {
        Connection connection=mock(Connection.class);
        assertThatThrownBy(()->ManagedProviderProbe.ownedAccountAbsent(connection,"fixture' OR 1=1")).hasMessage("OWNED_ACCOUNT_FORMAT");
        verifyNoInteractions(connection);
    }
}
