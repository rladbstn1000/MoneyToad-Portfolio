package com.potg.don.maintenance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CleanupReadOnlyGuardTest {
    private static final String TAG = "READ_ONLY_GUARD_REJECTED";

    record Fixture(Connection raw, PreparedStatement statement, ResultSet rows,
        DatabaseMetaData metadata, ResultSetMetaData resultMetadata, Connection guarded) { }
    private Fixture fixture() throws Exception {
        Connection raw=mock(Connection.class); PreparedStatement statement=mock(PreparedStatement.class);
        ResultSet rows=mock(ResultSet.class); DatabaseMetaData metadata=mock(DatabaseMetaData.class);
        ResultSetMetaData resultMetadata=mock(ResultSetMetaData.class);
        when(raw.getCatalog()).thenReturn("owned_fixture");
        when(raw.prepareStatement(anyString())).thenReturn(statement);
        when(raw.getMetaData()).thenReturn(metadata);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(resultMetadata);
        when(metadata.getColumns(anyString(),isNull(),anyString(),isNull())).thenReturn(rows);
        return new Fixture(raw,statement,rows,metadata,resultMetadata,CleanupReadOnlyGuard.wrap(raw));
    }
    private static void rejected(Executable call) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(CleanupFailure.class,
            failure->assertThat(failure.code()).isEqualTo(CleanupFailure.Code.READ_ONLY_GUARD_REJECTED))
            .hasMessage(TAG).hasNoCause();
    }

    @ParameterizedTest @ValueSource(strings={
        "INSERT INTO users(id) VALUES(1)", "UPDATE users SET name='fixture' WHERE id=1",
        "DELETE FROM users WHERE id=1", "CREATE TABLE escaped(id INT)", "DROP TABLE users",
        "ALTER TABLE users ADD COLUMN escaped INT", "TRUNCATE users", "CALL unsafe_procedure()",
        "SELECT UTC_TIMESTAMP(6); DELETE FROM users", "SELECT UTC_TIMESTAMP(6) INTO OUTFILE 'fixture'",
        "SELECT id,file_id FROM users WHERE id=? FOR UPDATE NOWAIT",
        "SELECT /* executable change */ UTC_TIMESTAMP(6)", "SET SESSION TRANSACTION READ WRITE",
        "/*!50000 DELETE FROM users */"
    })
    void unreviewedSqlIsRejectedBeforeAnyPreparedStatementDelegate(String sql) throws Exception {
        Fixture f=fixture(); rejected(()->f.guarded().prepareStatement(sql));
        verify(f.raw(),never()).prepareStatement(anyString()); verifyNoInteractions(f.statement());
    }

    @Test void reviewedSelectWithParametersAndRowsDelegatesOnce() throws Exception {
        Fixture f=fixture(); when(f.rows().next()).thenReturn(true,false); when(f.rows().getLong(1)).thenReturn(17L);
        PreparedStatement query=f.guarded().prepareStatement("SELECT id,file_id FROM users WHERE id=?");
        query.setLong(1,17L); try(ResultSet rows=query.executeQuery()) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(17L); assertThat(rows.next()).isFalse();
        }
        query.close(); verify(f.statement()).setLong(1,17L); verify(f.statement()).executeQuery(); verify(f.rows()).close();
        verify(f.statement(),never()).executeUpdate();
    }

    @Test void harmlessWhitespaceCaseAreAcceptedWithoutBroadeningSql() {
        assertThat(CleanupReadOnlyGuard.allowedSelect(" \nselect UTC_TIMESTAMP(6)\t")).isTrue();
        assertThat(CleanupReadOnlyGuard.allowedSelect(null)).isFalse();
        assertThat(CleanupReadOnlyGuard.allowedSelect("SELECT 1")).isFalse();
        assertThat(CleanupReadOnlyGuard.allowedSelect("SELECT UTC_TIMESTAMP(6); ")).isFalse();
    }

    @Test void preparedStatementCannotExecuteUpdatesBatchesAlternateSqlOrExposeConnection() throws Exception {
        Fixture f=fixture(); PreparedStatement query=f.guarded().prepareStatement("SELECT UTC_TIMESTAMP(6)");
        for(Executable operation:List.<Executable>of(query::executeUpdate,query::executeLargeUpdate,query::execute,
            query::addBatch,query::executeBatch,query::executeLargeBatch,()->query.addBatch("DELETE FROM users"),
            ()->query.executeQuery("DELETE FROM users"),()->query.execute("DELETE FROM users"),
            ()->query.executeUpdate("DELETE FROM users"),query::getGeneratedKeys)) rejected(operation);
        assertThat(query.getConnection()).isSameAs(f.guarded());
        rejected(()->query.getConnection().createStatement());
        rejected(()->query.unwrap(PreparedStatement.class)); assertThat(query.isWrapperFor(PreparedStatement.class)).isFalse();
        verifyNoInteractions(f.statement());
    }

    @Test void genericConnectionMutationAndEscapePathsAreRejected() throws Exception {
        Fixture f=fixture();
        for(Executable operation:List.<Executable>of(f.guarded()::createStatement,()->f.guarded().prepareCall("CALL unsafe()"),
            f.guarded()::commit,()->f.guarded().setAutoCommit(true),()->f.guarded().setReadOnly(false),
            ()->f.guarded().setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED),
            ()->f.guarded().unwrap(Connection.class),()->f.guarded().nativeSQL("DELETE FROM users"),
            ()->f.guarded().prepareStatement("SELECT UTC_TIMESTAMP(6)",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_UPDATABLE))) rejected(operation);
        assertThat(f.guarded().isWrapperFor(Connection.class)).isFalse();
        f.guarded().rollback(); verify(f.raw()).rollback(); verify(f.raw(),never()).commit();
        verify(f.raw(),never()).createStatement(); verify(f.raw(),never()).prepareCall(anyString());
    }

    @Test void rowMutationAndStatementEscapeCannotReachDriver() throws Exception {
        Fixture f=fixture(); PreparedStatement query=f.guarded().prepareStatement("SELECT UTC_TIMESTAMP(6)");
        ResultSet rows=query.executeQuery(); assertThat(rows.getStatement()).isSameAs(query);
        for(Executable operation:List.<Executable>of(rows::updateRow,rows::insertRow,rows::deleteRow,rows::moveToInsertRow,
            ()->rows.updateString(1,"fixture"),()->rows.updateObject(1,"fixture"),()->rows.unwrap(ResultSet.class),
            ()->rows.getObject(1,Connection.class),()->rows.getMetaData().unwrap(ResultSetMetaData.class))) rejected(operation);
        verify(f.rows(),never()).updateRow(); verify(f.rows(),never()).insertRow(); verify(f.rows(),never()).deleteRow();
        verify(f.rows(),never()).getStatement();
        when(f.rows().getObject(1)).thenReturn(f.raw()); rejected(()->rows.getObject(1));
    }

    @Test void metadataConnectionAndInternalStatementStayEncapsulated() throws Exception {
        Fixture f=fixture(); DatabaseMetaData metadata=f.guarded().getMetaData();
        assertThat(metadata.getConnection()).isSameAs(f.guarded());
        rejected(()->metadata.getConnection().prepareStatement("DELETE FROM users"));
        verify(f.raw(),never()).prepareStatement(anyString());
        rejected(()->metadata.unwrap(DatabaseMetaData.class)); assertThat(metadata.isWrapperFor(DatabaseMetaData.class)).isFalse();
        ResultSet rows=metadata.getColumns("owned_fixture",null,"demo_visit",null);
        assertThat(rows.getStatement()).isNull(); rejected(()->rows.unwrap(ResultSet.class));
        verify(f.rows(),never()).getStatement();
        rejected(()->metadata.getColumns("different_fixture",null,"demo_visit",null));
        rejected(()->metadata.getColumns("owned_fixture",null,"users",null));
        rejected(()->metadata.getTables(null,null,null,null));
        verify(f.metadata(),times(1)).getColumns(anyString(),any(),anyString(),any());
    }

    @Test void driverInternalMetadataQueryIsNotReclassifiedAsApplicationSql() throws Exception {
        Fixture f=fixture(); Statement internal=mock(Statement.class);
        when(f.raw().createStatement()).thenReturn(internal);
        when(f.metadata().getColumns("owned_fixture",null,"demo_visit",null)).thenAnswer(invocation->{
            // Driver-owned behavior is opaque to the application wrapper.
            f.raw().createStatement().executeQuery("SELECT DRIVER_INTERNAL_METADATA"); return f.rows();
        });
        ResultSet returned=f.guarded().getMetaData().getColumns("owned_fixture",null,"demo_visit",null);
        assertThat(returned).isNotSameAs(f.rows()); assertThat(returned.getStatement()).isNull();
        verify(internal).executeQuery("SELECT DRIVER_INTERNAL_METADATA");
    }

    @Test void diagnosticsAndObjectMethodsContainOnlyFixedTag() throws Exception {
        Fixture f=fixture(); assertThat(f.guarded().toString()).isEqualTo("CleanupReadOnlyGuard");
        assertThat(f.guarded().equals(f.guarded())).isTrue(); assertThat(f.guarded().equals(f.raw())).isFalse();
        rejected(()->f.guarded().prepareStatement("DELETE FROM private_canary_secret_table"));
        rejected(()->f.guarded().prepareStatement("x".repeat(8193)));
    }
}
