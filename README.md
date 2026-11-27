# vsql-java

A JDBC driver for [vsql](https://github.com/elliotchance/vsql), a single file or
in-memory SQL database. libvsql (the vsql library) is in the jar for Linux,
macOS (x86-64 and ARM64) and Windows (x86-64), so there's nothing else to
install.

```xml
<dependency>
  <groupId>io.github.elliotchance</groupId>
  <artifactId>vsql</artifactId>
  <version>1.0.0</version>
</dependency>
```

```java
try (Connection conn = DriverManager.getConnection("jdbc:vsql:test.vsql")) {
    try (Statement s = conn.createStatement()) {
        s.execute("CREATE TABLE foo (x INTEGER, y VARCHAR(10))");
    }
    try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (x, y) VALUES (?, ?)")) {
        s.setInt(1, 1);
        s.setString(2, "a");
        s.executeUpdate();
    }
    try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x, y FROM foo")) {
        while (rs.next()) {
            System.out.println(rs.getInt("x") + " " + rs.getString("y"));
        }
    }
}
```

The URL is `jdbc:vsql:` and the path of the database file (it's created if it
doesn't exist), or `jdbc:vsql::memory:` for a database that only exists for the
life of the connection. `VsqlDataSource` is a `DataSource` for a URL (such as
for a connection pool).

The database is a catalog, named by the file name without its extension (such
as `test` for `test.vsql`, or `:memory:`). `conn.setSchema()` changes the
schema of unqualified table names (`PUBLIC` by default), and the name is exact
(as `conn.getSchema()` returns it), so `CREATE SCHEMA foo` is `"FOO"`.
`conn.setCatalog()` changes the current catalog. A schema or catalog that
doesn't exist is SQLSTATE `3F000` or `3D000`.

A connection's initial settings can be connection properties, in the
`Properties` or after the path in the URL, each after a `;` (so a path that
contains a `;` can't be in a URL):

```java
DriverManager.getConnection("jdbc:vsql:test.vsql;readOnly=true;autoCommit=false");
```

| Property               | Values                                                                  | Default          |
|------------------------|-------------------------------------------------------------------------|------------------|
| `autoCommit`           | `true`, `false`                                                         | `true`           |
| `readOnly`             | `true`, `false`                                                         | `false`          |
| `transactionIsolation` | `READ_UNCOMMITTED`, `READ_COMMITTED`, `REPEATABLE_READ`, `SERIALIZABLE` | `READ_COMMITTED` |

A property in the URL replaces the same property in the `Properties` (or the
one set on a `VsqlDataSource`, which has a setter for each). An invalid value,
or an unknown property in the URL, is SQLSTATE `08001`. Unknown properties in
the `Properties` are ignored, since they often have others (such as `user` and
`password`, vsql doesn't have users).

See [example/Example.java](example/Example.java) for a complete program, and the
[package documentation](src/main/java/io/github/elliotchance/vsql/package-info.java)
for the Java type of each SQL type.

## Requirements

Java 22 or later. libvsql is called with the
[Foreign Function and Memory API](https://openjdk.org/jeps/454), which needs
native access to be enabled, otherwise the JVM prints a warning (and future
versions will refuse):

```sh
java --enable-native-access=ALL-UNNAMED ...
```

The libraries work on Linux with glibc 2.17 or later, macOS 11 or later, and
Windows 10 or later. vsql doesn't have Windows ARM64 C yet.

## Transactions

Auto-commit is on: each statement is committed when it finishes (or when its
result set is closed). With `conn.setAutoCommit(false)`, the first statement
starts a transaction, which is ended by `conn.commit()` or `conn.rollback()`,
and can have savepoints:

```java
conn.setAutoCommit(false);
s.execute("INSERT INTO foo (x) VALUES (1)");
Savepoint sp = conn.setSavepoint();
s.execute("INSERT INTO foo (x) VALUES (2)");
conn.rollback(sp); // only 1 is inserted
conn.commit();
```

An error that rolls back the transaction (such as a `40001` serialization
failure) also ends it, and the next statement starts a new one. `COMMIT` and
`ROLLBACK` can't be executed as SQL: use `commit()` and `rollback()`.

Each transaction has the isolation level of `conn.setTransactionIsolation()`
(`READ COMMITTED` by default), and is `READ ONLY` (changing data is SQLSTATE
`25006`) if `conn.setReadOnly(true)`. vsql runs `READ UNCOMMITTED` as
`READ COMMITTED` (and it must be read only), and `REPEATABLE READ` as
`SERIALIZABLE`. vsql's own default is `SERIALIZABLE`, but a `SERIALIZABLE`
transaction that changes a table makes the other connections that use it wait
for it to finish (for up to 5 seconds, then it's SQLSTATE `40001`), so the
driver's default is `READ COMMITTED`. Statements that are executed with
auto-commit on are also `READ ONLY` if `conn.setReadOnly(true)`, but they have
vsql's default isolation level, since a `READ COMMITTED` statement that changes
data fails straight away (SQLSTATE `40001`) when another connection is changing
data, rather than waiting for it.

## Positioned updates

A statement's cursor can be named with `setCursorName()` before it's executed,
so that a positioned `UPDATE` or `DELETE` (`WHERE CURRENT OF`) on another
statement of the connection can change the row that its result set is on:

```java
Statement s = conn.createStatement();
s.setCursorName("c");
ResultSet rs = s.executeQuery("SELECT x FROM foo FOR UPDATE");
try (Statement u = conn.createStatement()) {
    while (rs.next()) {
        u.executeUpdate("UPDATE foo SET x = x + 1 WHERE CURRENT OF c");
    }
}
```

The name is an identifier (so `c` is the same cursor as `C`, but not as
`"c"`), and two statements of a connection can't have the same one.
`rs.getCursorName()` is the name as it was given, or a unique one that starts
with `SQL_CUR` if the statement wasn't given one. A positioned
statement can only be prepared (`prepareStatement()`) while the cursor is open,
and it can't be executed again once the cursor's statement has executed
something else. With auto-commit on, the changes are committed when the result
set is closed (or after its last row).

## Types

| SQL                        | `getObject`          | `getObject(i, Class)`      |
|----------------------------|----------------------|----------------------------|
| `SMALLINT`, `INTEGER`      | `Integer`            | any number                 |
| `BIGINT`                   | `Long`               | any number                 |
| `REAL`                     | `Float`              | any number                 |
| `DOUBLE PRECISION`         | `Double`             | any number                 |
| `DECIMAL`, `NUMERIC`       | `BigDecimal`         | any number                 |
| `DECFLOAT`                 | `BigDecimal`         | any number                 |
| `CHARACTER`, `VARCHAR`     | `String`             | `String`, `Clob`           |
| `CLOB`                     | `Clob`               | `String`, `Clob`           |
| `BINARY`, `VARBINARY`      | `byte[]`             | `byte[]`, `Blob`           |
| `BLOB`                     | `Blob`               | `byte[]`, `Blob`           |
| `BOOLEAN`                  | `Boolean`            | `Boolean`                  |
| `DATE`                     | `java.sql.Date`      | `LocalDate`                |
| `TIME`                     | `java.sql.Time`      | `LocalTime`                |
| `TIME WITH TIME ZONE`      | `OffsetTime`         | `OffsetTime`, `LocalTime`  |
| `TIMESTAMP`                | `java.sql.Timestamp` | `LocalDateTime`            |
| `TIMESTAMP WITH TIME ZONE` | `OffsetDateTime`     | `OffsetDateTime`, `Instant` |
| `INTERVAL YEAR TO MONTH`, `INTERVAL YEAR`, `INTERVAL MONTH` | `Period` | `Period` |
| the other `INTERVAL`s      | `Duration`           | `Duration`                 |

`java.sql.Date`, `Time` and `Timestamp` are in the JVM's time zone (or a
`Calendar`'s), and keep their milliseconds. A `TIME WITH TIME ZONE` that's read
as a `Time`, `Timestamp` or `LocalTime` is converted to the JVM's time zone
(like a `TIMESTAMP WITH TIME ZONE` is) as if it were on 1970-01-01, so
`TIME '10:00:00+05:00'` is `05:00:00` in UTC (and a `Timestamp` of it can be on
the day before or after). Use `getObject(i, OffsetTime.class)` to read it
exactly.

JDBC doesn't have `DECFLOAT` or intervals, so their `getColumnType` is
`Types.DECIMAL` and `Types.OTHER`. A day of a day-time interval is 24 hours (as
it is in SQL), and `getString` of an interval is the string in its literal,
such as `"1-02"` for `INTERVAL '1-02' YEAR(2) TO MONTH`. `getString` of a
binary string is its octets in hex. vsql doesn't have locators, so a `Blob` or
`Clob` is a copy of the value in memory (and so is one from `createBlob` or
`createClob`, which can be a parameter).

Parameters are `?`, and each Java type (including the `java.time` types,
`byte[]`, `Blob`, `Clob`, `Period` and `Duration`) is sent as an SQL type,
which is cast to the type it's used as. vsql determines the type of each
parameter from where it's used (such as the type of `x` in `x = ?`), which is
what `getParameterMetaData()` describes.

## Scrollable result sets

A result set is `TYPE_FORWARD_ONLY` by default: its cursor is closed (which
commits the statement, with auto-commit on) after the last row. A
`TYPE_SCROLL_INSENSITIVE` result set can move to any row (with `previous()`,
`first()`, `last()`, `absolute()`, `relative()` and the others), and its cursor
is a copy of the result, which doesn't see changes and stays open until the
result set is closed:

```java
try (Statement s = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
    rs.last();
    System.out.println(rs.getRow() + " rows, the last is " + rs.getInt(1));
    rs.absolute(-2); // the row before the last
}
```

## Errors

An error from vsql is an `SQLException` with its SQLSTATE (`getSQLState()`),
as the JDBC subclass for the SQLSTATE's class, such as `SQLSyntaxErrorException`
for `42` (`42P01` is an undefined table) and
`SQLIntegrityConstraintViolationException` for `23`. Each diagnostic after the
first is chained to it (`getNextException()`).

It also implements `VsqlException`, whose `getDiagnostics()` has what JDBC
doesn't: the violated constraint, its table, an unknown column, an invalid
cursor, and whether vsql dropped some of the conditions:

```java
} catch (SQLIntegrityConstraintViolationException e) {
    if (e instanceof VsqlException v) {
        System.out.println(v.getDiagnostics().constraintName()); // such as "FOO_PK"
    }
}
```

## Metadata

`DatabaseMetaData.getDatabaseProductVersion()` is the vsql release that libvsql
is from, and `getTypeInfo()` describes each data type. The methods that
describe what vsql supports (such as `supportsFullOuterJoins()`,
`getSQLKeywords()`, `nullsAreSortedLow()` and `getMaxTableNameLength()`) are
read from libvsql where it has them (`SQLGetInfo`, and `SQLGetFeatureInfo` for
the features of the SQL standard that vsql supports). `VsqlDatabaseMetaData`
(`conn.getMetaData().unwrap(VsqlDatabaseMetaData.class)`) also has how libvsql
was built (`getBuildCommit()`, `getBuildDate()`, `getVVersion()`, `getVCommit()`,
`getCCompiler()`, `getOperatingSystem()` and `getMachine()`) and the file
format versions of the database (`getFileReadVersion()` and
`getFileWriteVersion()`).

## What isn't supported

Scroll sensitive or updatable result sets, generated keys, stored procedures,
`NClob`, statement cancellation (the query timeout isn't used), and the
`DatabaseMetaData` methods that describe tables (such as `getTables`). These
throw `SQLFeatureNotSupportedException`.

## Developing

libvsql is built from the vsql amalgamation (`internal/amalgamation`) for this
machine, which needs `make` and a C compiler (MinGW-w64 gcc in an MSYS2 shell
on Windows):

```sh
scripts/build-libvsql.sh
mvn verify
```

To update the amalgamation to the latest vsql release (or a version):

```sh
scripts/update-amalgamation.sh
```

`src/main/java/io/github/elliotchance/vsql/Unsupported*.java` are generated by
`java scripts/GenerateUnsupported.java`.

## Releasing

Publish a GitHub release with the tag `vVERSION` (such as `v1.0.0`). The
release workflow builds libvsql for each OS and architecture, and publishes
the jar to Maven Central with that version. It needs these secrets:
`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a user token from
[central.sonatype.com](https://central.sonatype.com)), and `GPG_PRIVATE_KEY`
and `GPG_PASSPHRASE`.
