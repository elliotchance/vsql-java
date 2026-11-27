package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Describing a column of a type, see Column. */
class ColumnTest {
    static Column column(short type) {
        return new Column("x", type, 10, (short) 2, true);
    }

    @Test
    void typeNames() {
        assertEquals("UNKNOWN", column((short) 999).typeName());
        assertEquals("INTERVAL DAY TO SECOND", column(Native.SQL_INTERVAL_DAY_TO_SECOND).typeName());
    }

    @Test
    void precision() {
        assertEquals(5, column(Native.SQL_SMALLINT).precision());
        assertEquals(10, column(Native.SQL_INTEGER).precision());
        assertEquals(19, column(Native.SQL_BIGINT).precision());
        assertEquals(7, column(Native.SQL_REAL).precision());
        assertEquals(15, column(Native.SQL_FLOAT).precision());
        assertEquals(15, column(Native.SQL_DOUBLE).precision());
        assertEquals(1, column(Native.SQL_BOOLEAN).precision());
        assertEquals(10, column(Native.SQL_BLOB).precision());
    }

    @Test
    void scale() {
        assertEquals(2, column(Native.SQL_NUMERIC).scale());
        assertEquals(0, column(Native.SQL_INTEGER).scale());
    }

    @Test
    void kinds() {
        assertTrue(column(Native.SQL_CLOB).isCharacter());
        assertFalse(column(Native.SQL_INTEGER).isCharacter());
        assertTrue(column(Native.SQL_VARBINARY).isBinary());
        assertFalse(column(Native.SQL_VARCHAR).isBinary());
        assertTrue(column(Native.SQL_INTERVAL_YEAR).isInterval());
        assertTrue(column(Native.SQL_INTERVAL_MINUTE_TO_SECOND).isInterval());
        assertFalse(column((short) (Native.SQL_INTERVAL_MINUTE_TO_SECOND + 1)).isInterval());
        assertFalse(column(Native.SQL_INTEGER).isInterval());
    }
}
