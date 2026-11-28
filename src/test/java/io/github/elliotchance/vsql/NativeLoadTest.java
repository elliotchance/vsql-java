package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A libvsql that can't be loaded (from vsql.library.path) is an error when
 * the driver is first used. It's its own class, since each test class has its
 * own JVM (see pom.xml).
 */
class NativeLoadTest {
    @TempDir
    Path tmp;

    @Test
    void cantLoad() {
        System.setProperty("vsql.library.path", tmp.resolve("nope").toString());
        assertThrows(ExceptionInInitializerError.class, () -> DriverManager.getConnection("jdbc:vsql::memory:"));
    }
}
