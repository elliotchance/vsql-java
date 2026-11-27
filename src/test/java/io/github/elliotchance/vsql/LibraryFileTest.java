package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Where libvsql is written to be loaded, see LibraryFile. */
class LibraryFileTest {
    private static final String FILE = "libvsql.so";
    private static final byte[] LIBRARY = "the library".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "another library".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    private Path shared() {
        return LibraryFile.sharedFolder(tmp, LibraryFile.sha256(LIBRARY));
    }

    private void assumePosix() {
        assumeTrue(tmp.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "the file system doesn't have POSIX permissions");
    }

    private static void assertNotWritableByOthers(Path path) throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE), permissions.toString());
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE), permissions.toString());
    }

    /** A folder of the temporary folder that isn't the shared one. */
    private void assertPrivateFolder(Path path) throws IOException {
        assertNotEquals(shared(), path.getParent());
        assertEquals(tmp, path.getParent().getParent());
        assertEquals(FILE, path.getFileName().toString());
        assertArrayEquals(LIBRARY, Files.readAllBytes(path));
    }

    @Test
    void writtenOnce() throws IOException {
        Path path = LibraryFile.extract(tmp, FILE, LIBRARY);
        assertEquals(shared().resolve(FILE), path);
        assertArrayEquals(LIBRARY, Files.readAllBytes(path));
        Object key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();

        // The second time, it's the same file (it isn't replaced).
        assertEquals(path, LibraryFile.extract(tmp, FILE, LIBRARY));
        assertEquals(key, Files.readAttributes(path, BasicFileAttributes.class).fileKey());
    }

    @Test
    void folderIsOwnerOnly() throws IOException {
        assumePosix();
        Path path = LibraryFile.extract(tmp, FILE, LIBRARY);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path.getParent()));
        assertNotWritableByOthers(path);
    }

    @Test
    void otherLibraryIsReplaced() throws IOException {
        Files.createDirectory(shared());
        Files.write(shared().resolve(FILE), OTHER);
        assertEquals(shared().resolve(FILE), LibraryFile.extract(tmp, FILE, LIBRARY));
        assertArrayEquals(LIBRARY, Files.readAllBytes(shared().resolve(FILE)));
    }

    /** A folder that the group or others can write to isn't used. */
    @ParameterizedTest
    @ValueSource(strings = {"rwxrwx---", "rwx----w-", "rwxrwxrwx"})
    void writableFolderIsNotUsed(String permissions) throws IOException {
        assumePosix();
        Files.createDirectory(shared());
        Files.write(shared().resolve(FILE), LIBRARY);
        Files.setPosixFilePermissions(shared(), PosixFilePermissions.fromString(permissions));

        Path path = LibraryFile.extract(tmp, FILE, LIBRARY);
        assertPrivateFolder(path);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path.getParent()));
    }

    /** A symbolic link to a folder isn't used, even if it's a safe folder. */
    @Test
    void linkedFolderIsNotUsed() throws IOException {
        assumePosix();
        Path real = Files.createDirectory(tmp.resolve("real"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.write(real.resolve(FILE), LIBRARY);
        Files.createSymbolicLink(shared(), real);

        Path path = LibraryFile.extract(tmp, FILE, LIBRARY);
        assertPrivateFolder(path);
        assertNotEquals(real, path.getParent());
    }

    /** A file in the shared folder isn't used if others can write to it. */
    @Test
    void writableFileIsReplaced() throws IOException {
        assumePosix();
        Files.createDirectory(shared());
        Path file = Files.write(shared().resolve(FILE), LIBRARY);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));

        assertEquals(file, LibraryFile.extract(tmp, FILE, LIBRARY));
        assertArrayEquals(LIBRARY, Files.readAllBytes(file));
        assertNotWritableByOthers(file);
    }

    /** A symbolic link in the shared folder is replaced with a file. */
    @Test
    void linkedFileIsReplaced() throws IOException {
        assumePosix();
        Path target = Files.write(tmp.resolve("target"), LIBRARY);
        Files.createDirectory(shared());
        Path file = Files.createSymbolicLink(shared().resolve(FILE), target);

        assertEquals(file, LibraryFile.extract(tmp, FILE, LIBRARY));
        assertFalse(Files.isSymbolicLink(file));
        assertTrue(Files.isRegularFile(file));
        assertArrayEquals(LIBRARY, Files.readAllBytes(file));
    }

    @Test
    void cantBeWritten() throws IOException {
        Path base = Files.write(tmp.resolve("not a folder"), OTHER);
        assertThrows(UncheckedIOException.class, () -> LibraryFile.extract(base, FILE, LIBRARY));
    }
}
