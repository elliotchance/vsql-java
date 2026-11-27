package io.github.elliotchance.vsql;

import java.sql.SQLWarning;

/**
 * The warnings of a connection, statement or result set (see getWarnings): the
 * diagnostic records of each libvsql routine that returned
 * SQL_SUCCESS_WITH_INFO, chained in the order they were raised. It's used
 * while holding the connection's lock.
 */
final class Warnings {
    private SQLWarning first;

    /** Adds a chain of warnings to the end, it does nothing for null. */
    void add(SQLWarning warning) {
        if (warning == null) {
            return;
        }
        if (first == null) {
            first = warning;
        } else {
            first.setNextWarning(warning);
        }
    }

    /** The first warning (the others are chained to it), or null. */
    SQLWarning get() {
        return first;
    }

    void clear() {
        first = null;
    }
}
