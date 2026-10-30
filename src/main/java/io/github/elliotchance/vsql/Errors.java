package io.github.elliotchance.vsql;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;

/**
 * Errors makes the SQLException for an SQLSTATE: the JDBC subclass for its
 * class (the first two characters), with the SQLSTATE as getSQLState.
 */
final class Errors {
    private Errors() {
    }

    static SQLException sql(String sqlstate, String message) {
        String cls = sqlstate.length() >= 2 ? sqlstate.substring(0, 2) : sqlstate;
        if (sqlstate.equals("HYC00")) {
            // Optional feature not implemented.
            return new SQLFeatureNotSupportedException(message, sqlstate);
        }

        return switch (cls) {
            case "08" -> new SQLNonTransientConnectionException(message, sqlstate);
            case "0A" -> new SQLFeatureNotSupportedException(message, sqlstate);
            case "22" -> new SQLDataException(message, sqlstate);
            case "23" -> new SQLIntegrityConstraintViolationException(message, sqlstate);
            case "28" -> new SQLInvalidAuthorizationSpecException(message, sqlstate);
            case "40" -> new SQLTransactionRollbackException(message, sqlstate);
            case "42" -> new SQLSyntaxErrorException(message, sqlstate);
            default -> new SQLException(message, sqlstate);
        };
    }

    static SQLFeatureNotSupportedException unsupported(String what) {
        return new SQLFeatureNotSupportedException("vsql: " + what + " is not supported", "0A000");
    }

    static SQLException closed(String what) {
        if (what.equals("connection")) {
            return new SQLNonTransientConnectionException("vsql: the connection is closed", "08003");
        }

        return new SQLException("vsql: the " + what + " is closed", "HY010");
    }
}
