import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Saxon creates many built-in function and helper objects with Class.newInstance(). The tracing
 * agent only records the ones a workload happens to use, so this generator registers the no-arg
 * constructor of every concrete Saxon class in the given packages for reflection.
 *
 * The JDK's own XPath 1.0 engine does the same for its function library, so JDK packages can be
 * listed with a "jrt:" prefix (e.g. jrt:com.sun.org.apache.xpath.internal.functions.).
 *
 * Usage: java GenReflect.java <saxon.jar> <output.json> <package-prefix>...
 */
public class GenReflect {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        List<String> prefixes = List.of(args).subList(2, args.length);
        List<String> entries = new ArrayList<>();
        try (URLClassLoader cl = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
             JarFile jf = new JarFile(jar.toFile())) {
            for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (!n.endsWith(".class") || n.contains("module-info")) continue;
                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                if (prefixes.stream().noneMatch(cn::startsWith)) continue;
                Class<?> c;
                try {
                    c = Class.forName(cn, false, cl);
                } catch (Throwable t) {
                    continue;
                }
                if (c.isInterface() || Modifier.isAbstract(c.getModifiers()) || c.isAnonymousClass()) continue;
                Constructor<?> k;
                try {
                    k = c.getDeclaredConstructor();
                } catch (Throwable t) {
                    continue;
                }
                entries.add("  {\"name\":\"" + c.getName() + "\",\"methods\":[{\"name\":\"<init>\",\"parameterTypes\":[]}]}");
            }
        }
        for (String prefix : prefixes) {
            if (!prefix.startsWith("jrt:")) continue;
            String pkg = prefix.substring(4);
            java.nio.file.FileSystem jrt = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
            Path dir = jrt.getPath("modules", "java.xml", pkg.replace('.', '/'));
            if (!Files.isDirectory(dir)) continue;
            try (var s = Files.list(dir)) {
                for (Path f : s.toList()) {
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".class")) continue;
                    String cn = pkg + n.substring(0, n.length() - 6);
                    Class<?> c;
                    try {
                        c = Class.forName(cn, false, ClassLoader.getSystemClassLoader());
                        c.getDeclaredConstructor();
                    } catch (Throwable t) {
                        continue;
                    }
                    if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) continue;
                    entries.add("  {\"name\":\"" + c.getName() + "\",\"methods\":[{\"name\":\"<init>\",\"parameterTypes\":[]}]}");
                }
            }
        }
        Files.writeString(Path.of(args[1]), "[\n" + String.join(",\n", entries) + "\n]\n");
        System.out.println(entries.size() + " classes -> " + args[1]);
    }
}
