package io.cpiwb.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Whether a path is inside the project folder, following symbolic links: a link inside the project that
 * points outside it counts as outside. A comparison of the paths as written is not enough, because reading
 * a file follows its links.
 */
public final class ProjectPaths {

    private ProjectPaths() {
    }

    /** True when {@code path} (which may not exist yet) is the project folder or below it. */
    public static boolean inside(Path root, Path path) {
        try {
            return real(path).startsWith(root.toRealPath());
        } catch (IOException e) {
            return false; // e.g. a dangling link: where it leads is not known
        }
    }

    /** A checked path for I/O. Keep the logical path separately for project-relative names. */
    static Path checked(Path root, Path path) throws IOException {
        return checked(root.toRealPath(), path, label(root, path));
    }

    private static Path checked(Path realRoot, Path path, String label) throws IOException {
        Path resolved;
        try {
            resolved = real(path);
        } catch (IOException e) {
            throw new IOException("Cannot resolve project path: " + label + " (broken link or unavailable path)", e);
        }
        if (!resolved.startsWith(realRoot)) {
            throw new IOException("Path is outside the project: " + label + " (a link leads outside)");
        }
        return resolved;
    }

    /** Project-relative label (including the alias the user sees). */
    static String label(Path root, Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    record Entry(Path path, Path resolved, boolean directory, String error) {
        boolean regularFile() {
            return resolved != null && Files.isRegularFile(resolved);
        }

        void requireReadable() throws IOException {
            if (error != null) {
                throw new IOException(error);
            }
        }
    }

    /** Enumerates only checked directories and keeps refused paths as diagnostics. Also checks junctions on Windows. */
    static List<Entry> scan(Path root, Path start, int depth, Predicate<Path> include) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (Files.notExists(start, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        scan(root, root.toRealPath(), start, null, depth, include, new HashSet<>(), out);
        out.sort(Comparator.comparing(Entry::path));
        return out;
    }

    /**
     * As {@link #scan}, with each file or folder once: a link inside the project to another place inside it (an
     * alias folder) would otherwise list the same files under two names. Of the names of one real path, the first
     * in path order is kept; refused entries are all kept.
     */
    static List<Entry> scanUnique(Path root, Path start, int depth, Predicate<Path> include) throws IOException {
        Set<Path> seen = new HashSet<>();
        return scan(root, start, depth, include).stream()
                .filter(e -> e.resolved() == null || seen.add(e.resolved())).toList();
    }

    /**
     * {@code realParent} is the checked real path of {@code path}'s parent, or null for the start. A child that is
     * neither a symbolic link nor another reparse point (a Windows junction reads as "other") is its parent's real
     * path plus its name, so only links and the start pay for resolving the real path.
     */
    private static void scan(Path root, Path realRoot, Path path, Path realParent, int depth, Predicate<Path> include,
                             Set<Path> parents, List<Entry> out) throws IOException {
        if (!include.test(path)) {
            return;
        }
        Path resolved = null;
        Boolean directory = null;
        if (realParent != null) {
            try {
                BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!a.isSymbolicLink() && !a.isOther()) {
                    resolved = realParent.resolve(path.getFileName().toString());
                    directory = a.isDirectory();
                }
            } catch (IOException e) {
                // resolved below, which reports what is wrong with the path
            }
        }
        if (resolved == null) {
            try {
                resolved = checked(realRoot, path, label(root, path));
            } catch (IOException e) {
                out.add(new Entry(path, null, false, e.getMessage()));
                return;
            }
            directory = Files.isDirectory(resolved);
        }
        if (directory && parents.contains(resolved)) {
            out.add(new Entry(path, null, false, "Project directory link forms a cycle: " + label(root, path)));
            return;
        }
        out.add(new Entry(path, resolved, directory, null));
        if (depth > 0 && directory) {
            parents.add(resolved);
            try (var children = Files.newDirectoryStream(resolved)) {
                for (Path child : children) {
                    scan(root, realRoot, path.resolve(child.getFileName().toString()), resolved, depth - 1, include, parents, out);
                }
            } finally {
                parents.remove(resolved);
            }
        }
    }

    /**
     * The real path: for a path that does not exist yet (a file to write), that of its nearest existing
     * ancestor with the rest appended. A dangling link throws, so a write cannot create its target.
     */
    private static Path real(Path path) throws IOException {
        Path abs = path.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null) {
            try {
                Files.readAttributes(existing, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                existing = existing.getParent();
                continue;
            }
            return existing.toRealPath().resolve(existing.relativize(abs)).normalize();
        }
        throw new NoSuchFileException(abs.toString());
    }
}
