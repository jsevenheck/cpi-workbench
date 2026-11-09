package io.cpiwb.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

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

    /**
     * The real path: for a path that does not exist yet (a file to write), that of its nearest existing
     * ancestor with the rest appended. A dangling link throws, so a write cannot create its target.
     */
    private static Path real(Path path) throws IOException {
        Path abs = path.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return abs;
        }
        return existing.toRealPath().resolve(existing.relativize(abs)).normalize();
    }
}
