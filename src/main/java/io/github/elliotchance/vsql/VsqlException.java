package io.github.elliotchance.vsql;

/**
 * Every SQLException from vsql (an error from libvsql, rather than the driver)
 * implements VsqlException, which has the diagnostic fields that JDBC doesn't
 * have, such as the name of a violated constraint:
 *
 * <pre>{@code
 * try {
 *     stmt.executeUpdate("INSERT INTO foo (id) VALUES (1)");
 * } catch (SQLIntegrityConstraintViolationException e) {
 *     if (e instanceof VsqlException v) {
 *         System.out.println(v.getDiagnostics().constraintName());
 *     }
 * }
 * }</pre>
 *
 * <p>The exception is still the JDBC subclass for its SQLSTATE (see the
 * package documentation), and each diagnostic after the first is chained to
 * it, see {@link java.sql.SQLException#getNextException()}.
 */
public interface VsqlException {
    /** The diagnostic fields that JDBC doesn't have. */
    VsqlDiagnostics getDiagnostics();
}
