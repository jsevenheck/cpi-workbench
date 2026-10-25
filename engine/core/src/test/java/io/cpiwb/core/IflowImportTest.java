package io.cpiwb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class IflowImportTest {

    private static Path zipOf(Path dir, Path zip) throws Exception {
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os);
             Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                z.putNextEntry(new ZipEntry(dir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void importsZipAndListsSteps(@TempDir Path tmp) throws Exception {
        Path fixture = Path.of(getClass().getResource("/iflow").toURI());
        Path zip = zipOf(fixture, tmp.resolve("Order_Replication.zip"));
        Path project = Files.createDirectories(tmp.resolve("project"));
        Map<String, Object> r = IflowImport.importZip(zip, project, null);
        assertEquals("iflows/Order_Replication", r.get("imported"));
        List<Object> steps = (List<Object>) r.get("steps");
        assertEquals(2, steps.size(), () -> Json.write(r));
        Map<String, Object> g = (Map<String, Object>) steps.get(0);
        assertEquals("groovy", g.get("type"));
        assertEquals("enrich", g.get("function"));
        assertEquals("groovy-2.x", g.get("profile"));
        assertEquals("iflows/Order_Replication/src/main/resources/script/enrich.groovy", g.get("file"));
        Map<String, Object> x = (Map<String, Object>) steps.get(1);
        assertEquals("xslt", x.get("type"));
        assertEquals("iflows/Order_Replication/src/main/resources/mapping/order.xsl", x.get("file"));
        assertTrue((Boolean) x.get("fileFound"));
    }

    @Test
    void rejectsZipSlip(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("../../outside.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        Path project = Files.createDirectories(tmp.resolve("project"));
        assertThrows(java.io.IOException.class, () -> IflowImport.importZip(zip, project, "evil"));
        assertTrue(Files.notExists(tmp.resolve("outside.txt")));
    }
}
