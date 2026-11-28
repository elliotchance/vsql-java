package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The JDBC subclass of an SQLException is chosen by its SQLSTATE. */
class ErrorsTest {
    static final Map<String, Class<? extends SQLException>> CLASSES = Map.of(
            "HYC00", SQLFeatureNotSupportedException.class,
            "08001", SQLNonTransientConnectionException.class,
            "0A000", SQLFeatureNotSupportedException.class,
            "22012", SQLDataException.class,
            "23505", SQLIntegrityConstraintViolationException.class,
            "28000", SQLInvalidAuthorizationSpecException.class,
            "40001", SQLTransactionRollbackException.class,
            "42601", SQLSyntaxErrorException.class,
            "HY000", SQLException.class);

    @Test
    void subclasses() {
        VsqlDiagnostics d = new VsqlDiagnostics(null, null, null, null, null, null, null, null, false);
        CLASSES.forEach((sqlstate, cls) -> {
            SQLException e = Errors.sql(sqlstate, "message");
            assertEquals(cls, e.getClass(), sqlstate);
            assertEquals(sqlstate, e.getSQLState());
            assertEquals("message", e.getMessage());

            e = Errors.sql(sqlstate, "message", d);
            assertInstanceOf(cls, e, sqlstate);
            assertEquals(sqlstate, e.getSQLState());
            assertEquals("message", e.getMessage());
            assertSame(d, assertInstanceOf(VsqlException.class, e).getDiagnostics());
        });
    }

    @Test
    void closed() {
        assertInstanceOf(SQLNonTransientConnectionException.class, Errors.closed("connection"));
        assertEquals("HY010", Errors.closed("statement").getSQLState());
    }
}
