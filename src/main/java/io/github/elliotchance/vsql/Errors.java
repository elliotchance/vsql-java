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
 * class (the first two characters), with the SQLSTATE as getSQLState. An error
 * from libvsql also implements VsqlException, with its diagnostics.
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

    /** An error that the driver makes, caused by another exception. */
    static SQLException sql(String sqlstate, String message, Throwable cause) {
        SQLException e = sql(sqlstate, message);
        e.initCause(cause);
        return e;
    }

    /** An error from libvsql, with its diagnostics. */
    static SQLException sql(String sqlstate, String message, VsqlDiagnostics d) {
        String cls = sqlstate.length() >= 2 ? sqlstate.substring(0, 2) : sqlstate;
        if (sqlstate.equals("HYC00")) {
            return new FeatureNotSupported(message, sqlstate, d);
        }

        return switch (cls) {
            case "08" -> new NonTransientConnection(message, sqlstate, d);
            case "0A" -> new FeatureNotSupported(message, sqlstate, d);
            case "22" -> new Data(message, sqlstate, d);
            case "23" -> new IntegrityConstraintViolation(message, sqlstate, d);
            case "28" -> new InvalidAuthorizationSpec(message, sqlstate, d);
            case "40" -> new TransactionRollback(message, sqlstate, d);
            case "42" -> new SyntaxError(message, sqlstate, d);
            default -> new Other(message, sqlstate, d);
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

    // The JDBC subclasses that implement VsqlException.

    private static final class Other extends SQLException implements VsqlException {
        private static final long serialVersionUID = 1L;
        private final VsqlDiagnostics diagnostics;

        Other(String message, String sqlstate, VsqlDiagnostics diagnostics) {
            super(message, sqlstate);
            this.diagnostics = diagnostics;
        }

        @Override
        public VsqlDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    private static final class NonTransientConnection extends SQLNonTransientConnectionException
            implements VsqlException {
        private static final long serialVersionUID = 1L;
        private final VsqlDiagnostics diagnostics;

        NonTransientConnection(String message, String sqlstate, VsqlDiagnostics diagnostics) {
            super(message, sqlstate);
            this.diagnostics = diagnostics;
        }

        @Override
        public VsqlDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    private static final class FeatureNotSupported extends SQLFeatureNotSupportedException implements VsqlException {
        private static final long serialVersionUID = 1L;
        private final VsqlDiagnostics diagnostics;

        FeatureNotSupported(String message, String sqlstate, VsqlDiagnostics diagnostics) {
            super(message, sqlstate);
            this.diagnostics = diagnostics;
        }

        @Override
        public VsqlDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    private static final class Data extends SQLDataException implements VsqlException {
        private static final long serialVersionUID = 1L;
        private final VsqlDiagnostics diagnostics;

        Data(String message, String sqlstate, VsqlDiagnostics diagnostics) {
            super(message, sqlstate);
            this.diagnostics = diagnostics;
        }

        @Override
        public VsqlDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    private static final class IntegrityConstraintViolation extends SQLIntegrityConstraintViolationException
            implements VsqlException {
        private static final long serialVersionUID = 1L;
        private final VsqlDiagnostics diagnostics;

        IntegrityConstraintViolation(String message, String sqlstate, VsqlDiagnostics diagnostics) {
            super(message, sqlstate);
            this.diagnostics = diagnostics;
        }

        @Override
        public VsqlDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    private static final class InvalidAuthorizationSpec extends SQLInvalidAuthorizationSpecException
            implements VsqlException {
        private static final long serialVersionUID = 1L;
        private final VsqlDiagnostics diagnostics;

        InvalidAuthorizationSpec(String message, String sqlstate, VsqlDiagnostics diagnostics) {
            super(message, sqlstate);
            this.diagnostics = diagnostics;
        }

        @Override
        public VsqlDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    private static final class TransactionRollback extends SQLTransactionRollbackException implements VsqlException {
        private static final long serialVersionUID = 1L;
        private final VsqlDiagnostics diagnostics;

        TransactionRollback(String message, String sqlstate, VsqlDiagnostics diagnostics) {
            super(message, sqlstate);
            this.diagnostics = diagnostics;
        }

        @Override
        public VsqlDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }

    private static final class SyntaxError extends SQLSyntaxErrorException implements VsqlException {
        private static final long serialVersionUID = 1L;
        private final VsqlDiagnostics diagnostics;

        SyntaxError(String message, String sqlstate, VsqlDiagnostics diagnostics) {
            super(message, sqlstate);
            this.diagnostics = diagnostics;
        }

        @Override
        public VsqlDiagnostics getDiagnostics() {
            return diagnostics;
        }
    }
}
