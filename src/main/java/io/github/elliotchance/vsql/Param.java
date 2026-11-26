package io.github.elliotchance.vsql;

/**
 * A parameter for Native.execute: the value is null, a Long (SQL_BIGINT), a
 * Double (SQL_DOUBLE), a String (SQL_CHAR) or a byte[] (SQL_BINARY), which is
 * cast to parameterType.
 */
record Param(short valueType, short parameterType, int columnSize, short decimalDigits, Object value) {
}
