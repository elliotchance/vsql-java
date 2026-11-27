package io.github.elliotchance.vsql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * LibraryFile writes libvsql (from the jar) to a file, so it can be loaded.
 *
 * <p>The file is in a folder of the temporary folder that's named by the user
 * and the library's checksum, so it's only written once, and every JVM of the
 * same user that uses the same library loads the same file. An existing file
 * is only used if it has the same checksum.
 *
 * <p>On Linux (and other systems with a shared /tmp), every user can create
 * files in the temporary folder, so another user could create the folder
 * first. Then they could replace the file between the checksum and the load
 * (and run their own code as this user), or stop the driver from loading at
 * all. So, where the file system has POSIX permissions, the folder is created
 * {@code rwx------}, and an existing folder or file is only used if it isn't a
 * symbolic link, is owned by this user, and can't be written by the group or
 * others. If the folder can't be used, the library is written to a new
 * temporary folder instead (which is deleted on exit, where possible). Without
 * POSIX permissions (Windows), the temporary folder is the user's own, so
 * there are no checks.
 */
final class LibraryFile {
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    private LibraryFile() {
    }

    /**
     * Writes a library to a file in the temporary folder base (unless it's
     * already there, see LibraryFile), and returns the file's path.
     *
     * @param base the temporary folder
     * @param file the name of the file, such as libvsql.so
     * @param library the contents of the file
     * @return the path of the file
     */
    static Path extract(Path base, String file, byte[] library) {
        String checksum = sha256(library);
        IOException shared;
        try {
            Path path = extractShared(sharedFolder(base, checksum), file, library, checksum);
            if (path != null) {
                return path;
            }
            shared = null;
        } catch (IOException e) {
            shared = e;
        }

        // The shared folder can't be used, so the library goes in a new folder
        // that only this JVM uses.
        try {
            Path dir = Files.createTempDirectory(base, "vsql-");
            dir.toFile().deleteOnExit();
            Path path = dir.resolve(file);
            Files.write(path, library);
            // Files are deleted on exit in the reverse order, so the file is
            // deleted before its folder. On Windows, a loaded library can't
            // be deleted, so they stay.
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            if (shared != null) {
                e.addSuppressed(shared);
            }
            throw new UncheckedIOException("vsql: can't write libvsql to " + base, e);
        }
    }

    /**
     * The folder that's shared by the JVMs of this user that use the library
     * with this checksum.
     *
     * @param base the temporary folder
     * @param checksum the library's checksum (see sha256)
     * @return the folder in base
     */
    static Path sharedFolder(Path base, String checksum) {
        String user = System.getProperty("user.name", "").replaceAll("[^A-Za-z0-9._-]", "_");
        return base.resolve("vsql-" + user + "-" + checksum.substring(0, 16));
    }

    /**
     * Writes the library to a file in the shared folder (unless it's already
     * there). It returns null if the folder isn't safe to use.
     */
    private static Path extractShared(Path dir, String file, byte[] library, String checksum)
            throws IOException {
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        UserPrincipal user = null;
        if (posix) {
            try {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (FileAlreadyExistsException e) {
                // It's checked below.
            }
            user = dir.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            if (!isSafe(dir, user, true)) {
                return null;
            }
        } else {
            Files.createDirectories(dir);
        }

        // Only this user can create, replace or change the files in the folder
        // now, so a file that's checked here is the file that's loaded.
        Path path = dir.resolve(file);
        if (isUsable(path, posix, user, checksum)) {
            return path;
        }
        Path tmp = Files.createTempFile(dir, "libvsql", ".tmp");
        Files.write(tmp, library);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Another JVM wrote it first (and on Windows, it can't be replaced
            // while it's loaded).
            Files.deleteIfExists(tmp);
            if (!isUsable(path, posix, user, checksum)) {
                throw e;
            }
        }

        return path;
    }

    /** Whether path is a safe (see isSafe) file with the checksum. */
    private static boolean isUsable(Path path, boolean posix, UserPrincipal user, String checksum)
            throws IOException {
        return (posix ? isSafe(path, user, false) : Files.isRegularFile(path))
                && sha256(Files.readAllBytes(path)).equals(checksum);
    }

    /**
     * Whether path is a folder (or a regular file) that isn't a symbolic link,
     * is owned by user, and can't be written by the group or others. It's
     * false if path doesn't exist.
     */
    private static boolean isSafe(Path path, UserPrincipal user, boolean folder) throws IOException {
        PosixFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return false;
        }
        Set<PosixFilePermission> permissions = attrs.permissions();

        return (folder ? attrs.isDirectory() : attrs.isRegularFile())
                && attrs.owner().equals(user)
                && !permissions.contains(PosixFilePermission.GROUP_WRITE)
                && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
    }

    /**
     * The SHA-256 of data, in hex.
     *
     * @param data the data
     * @return 64 hex digits
     */
    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
