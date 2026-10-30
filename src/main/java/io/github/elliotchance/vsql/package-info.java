/**
 * A JDBC driver for <a href="https://github.com/elliotchance/vsql">vsql</a>, a
 * single file or in-memory SQL database. libvsql (the vsql library) is in the
 * jar, so there's nothing else to install.
 *
 * <pre>{@code
 * try (Connection conn = DriverManager.getConnection("jdbc:vsql:test.vsql");
 *         PreparedStatement stmt = conn.prepareStatement("INSERT INTO foo (x) VALUES (?)")) {
 *     stmt.setInt(1, 123);
 *     stmt.executeUpdate();
 * }
 * }</pre>
 *
 * <p>The URL is {@code jdbc:vsql:} and the path of the database file (which is
 * created if it doesn't exist), or {@code jdbc:vsql::memory:} for a database
 * that only exists for the life of the connection. libvsql is called with the
 * Foreign Function and Memory API, so the JVM needs
 * {@code --enable-native-access=ALL-UNNAMED} (or the module's name), otherwise
 * it prints a warning.
 *
 * <h2>Transactions</h2>
 *
 * <p>Auto-commit is on: each statement is committed when it finishes (or when
 * its result set is closed). With {@code setAutoCommit(false)}, the first
 * statement starts a transaction, which is ended by {@code commit} or
 * {@code rollback}. vsql's transactions are READ COMMITTED. START TRANSACTION,
 * COMMIT and ROLLBACK can't be executed as statements.
 *
 * <h2>Parameters</h2>
 *
 * <p>Parameters are {@code ?}. Each Java type is sent as an SQL type, which is
 * cast to the type it's used as:
 *
 * <table class="striped">
 * <caption>Parameters</caption>
 * <thead><tr><th>Java</th><th>SQL</th></tr></thead>
 * <tbody>
 * <tr><td>null</td><td>NULL</td></tr>
 * <tr><td>boolean</td><td>BOOLEAN</td></tr>
 * <tr><td>byte, short, int, long, BigInteger</td><td>BIGINT (NUMERIC if it's out of range)</td></tr>
 * <tr><td>float, double</td><td>DOUBLE PRECISION</td></tr>
 * <tr><td>BigDecimal</td><td>NUMERIC</td></tr>
 * <tr><td>String</td><td>CHARACTER VARYING</td></tr>
 * <tr><td>java.sql.Date, LocalDate</td><td>DATE</td></tr>
 * <tr><td>java.sql.Time, LocalTime</td><td>TIME(6)</td></tr>
 * <tr><td>OffsetTime</td><td>TIME(6) WITH TIME ZONE</td></tr>
 * <tr><td>java.sql.Timestamp, LocalDateTime</td><td>TIMESTAMP(6)</td></tr>
 * <tr><td>OffsetDateTime, ZonedDateTime, Instant</td><td>TIMESTAMP(6) WITH TIME ZONE</td></tr>
 * </tbody>
 * </table>
 *
 * <h2>Columns</h2>
 *
 * <table class="striped">
 * <caption>Columns</caption>
 * <thead><tr><th>SQL</th><th>getObject</th><th>getObject(i, Class)</th></tr></thead>
 * <tbody>
 * <tr><td>SMALLINT, INTEGER</td><td>Integer</td><td>any number</td></tr>
 * <tr><td>BIGINT</td><td>Long</td><td>any number</td></tr>
 * <tr><td>REAL</td><td>Float</td><td>any number</td></tr>
 * <tr><td>DOUBLE PRECISION</td><td>Double</td><td>any number</td></tr>
 * <tr><td>DECIMAL, NUMERIC</td><td>BigDecimal</td><td>any number</td></tr>
 * <tr><td>CHARACTER, VARCHAR</td><td>String</td><td>String</td></tr>
 * <tr><td>BOOLEAN</td><td>Boolean</td><td>Boolean</td></tr>
 * <tr><td>DATE</td><td>java.sql.Date</td><td>LocalDate</td></tr>
 * <tr><td>TIME</td><td>java.sql.Time</td><td>LocalTime</td></tr>
 * <tr><td>TIME WITH TIME ZONE</td><td>OffsetTime</td><td>OffsetTime</td></tr>
 * <tr><td>TIMESTAMP</td><td>java.sql.Timestamp</td><td>LocalDateTime</td></tr>
 * <tr><td>TIMESTAMP WITH TIME ZONE</td><td>OffsetDateTime</td><td>OffsetDateTime, Instant</td></tr>
 * </tbody>
 * </table>
 *
 * <p>java.sql.Date, Time and Timestamp are in the JVM's time zone (or a
 * Calendar's), so use the java.time types with getObject(i, Class) to read a
 * value exactly.
 *
 * <h2>Errors</h2>
 *
 * <p>An error from vsql is an SQLException with its SQLSTATE (see
 * {@code getSQLState}), as the JDBC subclass for the SQLSTATE's class, such as
 * SQLSyntaxErrorException for 42 (42P01 is an undefined table) and
 * SQLIntegrityConstraintViolationException for 23.
 */
package io.github.elliotchance.vsql;
