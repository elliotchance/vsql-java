package io.github.elliotchance.vsql;

import java.io.Serializable;

/**
 * The fields of a vsql diagnostic (a condition) that JDBC doesn't have, see
 * {@link VsqlException}. Each name is null if it isn't known: the constraint
 * fields are set when a constraint is violated (SQLSTATE class 23), the
 * catalog, schema and table names are the table of the constraint, the column
 * name is set for an unknown column, and the cursor name for an invalid cursor
 * state (24000).
 *
 * @param constraintCatalog the catalog of the violated constraint (the database)
 * @param constraintSchema the schema of the violated constraint
 * @param constraintName the name of the violated constraint, such as "PK_FOO"
 * @param catalogName the catalog of the table (the database)
 * @param schemaName the schema of the table
 * @param tableName the table
 * @param columnName the column
 * @param cursorName the cursor
 * @param moreConditions true if the statement raised more conditions than vsql
 *     keeps (its DIAGNOSTICS SIZE), so some of them were dropped
 */
public record VsqlDiagnostics(String constraintCatalog, String constraintSchema, String constraintName,
        String catalogName, String schemaName, String tableName, String columnName, String cursorName,
        boolean moreConditions) implements Serializable {
    private static final long serialVersionUID = 1L;
}
