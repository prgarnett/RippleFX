package net.prgarnett;

import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.engine.GraphvizV8Engine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Java-native PNG renderer using the {@code graphviz-java} library with the
 * V8 engine provided by {@code graphviz-java-all-j2v8}.
 *
 * <p>Reads the {@code .dot} file already written by
 * {@link DotMakeNodes#saveTheFile()} and renders it to PNG entirely within
 * the JVM — no external {@code dot} binary is required.
 *
 * <p>Requires in pom.xml (the all-j2v8 artifact includes the core library,
 * so only one dependency is needed):
 * <pre>
 *   &lt;dependency&gt;
 *       &lt;groupId&gt;guru.nidi&lt;/groupId&gt;
 *       &lt;artifactId&gt;graphviz-java-all-j2v8&lt;/artifactId&gt;
 *       &lt;version&gt;0.18.1&lt;/version&gt;
 *   &lt;/dependency&gt;
 * </pre>
 *
 * <p>Note: {@code GraphvizV8Engine} must be used on Java 15+ because Nashorn
 * (used by {@code GraphvizJdkEngine}) was removed from the JDK in Java 15.
 */
public class JavaRenderer {

    private JavaRenderer() {}   // utility class

    /**
     * Renders a {@code .dot} file to PNG using the V8-backed graphviz-java engine.
     *
     * @param filepath  working directory — the same value passed to
     *                  {@link DotMakeNodes#setFilepath(String)}
     * @param relsFile  relationships CSV filename — used to derive the
     *                  {@code .dot} / {@code .png} paths, same value passed
     *                  to the {@link DotMakeNodes} constructor
     * @return          absolute path of the generated PNG, or {@code null}
     *                  if rendering failed
     */
    public static String render(String filepath, String relsFile) {
        String base    = stripExtension(relsFile);
        String dotPath = filepath + base + ".dot";
        String pngPath = filepath + base + ".png";

        System.out.println("Java renderer: reading " + dotPath);

        try (GraphvizV8Engine engine = new GraphvizV8Engine()) {
            String dotSource = Files.readString(Paths.get(dotPath));

            Graphviz.useEngine(engine);
            Graphviz.fromString(dotSource)
                    .render(Format.PNG)
                    .toFile(new File(pngPath));

            System.out.println("PNG rendered (Java-native V8) to: " + pngPath);
            return pngPath;

        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            System.err.println("graphviz-java-all-j2v8 not found on classpath: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("Java-native rendering failed: " + e.getMessage());
            return null;
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }
}