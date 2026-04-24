package net.prgarnett;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Reads a nodes CSV and a relationships CSV, produces a Graphviz DOT file,
 * and optionally renders it to PNG via the {@code dot} command-line tool.
 *
 * <p>Node CSV columns: type, attribute_label_N, value_N  (N = 1, 2, 3, ...)
 * <ul>
 *   <li>"name"  - used as the node label (required)</li>
 *   <li>"date"  - used for timeline ranking (dd/MM/yyyy)</li>
 *   <li>any label matching a known Graphviz node attribute is written directly
 *       into the DOT node definition (e.g. shape, fillcolor)</li>
 *   <li>any other label is written as a tooltip attribute</li>
 * </ul>
 *
 * <p>Edges CSV columns: match id 1, match id 2, attribute_label_N, value_N  (N = 1, 2, ...)
 * <ul>
 *   <li>known Graphviz edge attributes are written directly (e.g. style, color)</li>
 *   <li>unknown attributes are collected into a tooltip</li>
 * </ul>
 *
 * <p>Typical usage:
 * <pre>
 *   var maker = new DotMakeNodes("RippleEdges.csv");
 *   maker.setFilepath("/data/");
 *   maker.readTheNodesFile("Nodes.csv");
 *   maker.readTheRelsFile();
 *   maker.saveTheFile();
 *   maker.renderToPng();   // no-op with a clear message if dot is not installed
 * </pre>
 */
public class DotMakeNodes {

    // Graphviz node attributes we will pass through directly
    private static final Set<String> GRAPHVIZ_NODE_ATTRS = Set.of(
            "shape", "style", "fillcolor", "color", "fontcolor", "fontsize",
            "fontname", "width", "height", "fixedsize", "penwidth", "peripheries",
            "sides", "skew", "distortion", "orientation", "regular", "URL", "target",
            "tooltip", "bgcolor", "gradientangle", "margin"
    );

    // Graphviz edge attributes we will pass through directly
    private static final Set<String> GRAPHVIZ_EDGE_ATTRS = Set.of(
            "style", "color", "fontcolor", "fontsize", "fontname", "label",
            "arrowhead", "arrowtail", "arrowsize", "dir", "weight", "minlen",
            "penwidth", "constraint", "URL", "target", "tooltip", "ltail", "lhead"
    );

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final String relsFile;
    private String filepath = "";

    /** Keyed by node name (as it appears in the CSV), value = internal DOT id e.g. "Node_0" */
    private final Map<String, String> nameToId = new LinkedHashMap<>();

    private final List<NodeObject> nodeObjects = new ArrayList<>();
    private final List<String> edgeStatements = new ArrayList<>();

    // -------------------------------------------------------------------------

    public DotMakeNodes(String relsFile) {
        this.relsFile = relsFile;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void readTheNodesFile(String nodesFile) {
        int nodeCount = 0;
        try {
            var records = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build()
                    .parse(new FileReader(filepath + nodesFile));

            for (CSVRecord record : records) {
                var dataRow = record.toMap();
                String nodeId = "Node_" + nodeCount++;

                // --- Pass 1: collect paired (label, value) entries in order ---
                // Headers arrive in column order when iterated via toMap; use a
                // TreeMap so we sort by key to reconstruct attribute_label_N / value_N pairs.
                String type = dataRow.getOrDefault("type", "ellipse");
                String nodeLabel = null;
                LocalDate nodeDate = null;
                Map<String, String> graphvizAttrs = new LinkedHashMap<>();
                Map<String, String> extraAttrs = new LinkedHashMap<>();

                // Find the highest N present
                int maxN = 0;
                for (var key : dataRow.keySet()) {
                    if (key.startsWith("attribute_label_")) {
                        try {
                            int n = Integer.parseInt(key.substring("attribute_label_".length()));
                            if (n > maxN) maxN = n;
                        } catch (NumberFormatException ignored) {}
                    }
                }

                for (int n = 1; n <= maxN; n++) {
                    String labelKey = "attribute_label_" + n;
                    String valueKey = "value_" + n;
                    String attrLabel = dataRow.getOrDefault(labelKey, "").trim();
                    String attrValue = dataRow.getOrDefault(valueKey, "").trim();

                    if (attrLabel.isEmpty() || attrValue.isEmpty()) continue;

                    attrLabel = escapeString(attrLabel);
                    attrValue = escapeString(attrValue);

                    switch (attrLabel) {
                        case "name" -> {
                            nodeLabel = attrValue;
                            nameToId.put(attrValue, nodeId);
                        }
                        case "date" -> nodeDate = parseDate(attrValue);
                        default -> {
                            if (GRAPHVIZ_NODE_ATTRS.contains(attrLabel)) {
                                graphvizAttrs.put(attrLabel, attrValue);
                            } else {
                                extraAttrs.put(attrLabel, attrValue);
                            }
                        }
                    }
                }

                if (nodeLabel == null) {
                    System.err.println("Warning: Node_" + (nodeCount - 1) + " has no 'name' attribute – skipping.");
                    continue;
                }

                // --- Build the DOT attribute string ---
                var sb = new StringBuilder();
                sb.append("label=\"").append(wrapLabel(nodeLabel)).append("\"");

                for (var e : graphvizAttrs.entrySet()) {
                    sb.append(", ").append(e.getKey()).append("=\"").append(e.getValue()).append("\"");
                }

                // Non-graphviz attributes → tooltip (HTML escaped, newline separated)
                if (!extraAttrs.isEmpty()) {
                    var tip = new StringBuilder();
                    for (var e : extraAttrs.entrySet()) {
                        if (!tip.isEmpty()) tip.append("&#10;");
                        tip.append(e.getKey()).append(": ").append(e.getValue());
                    }
                    sb.append(", tooltip=\"").append(tip).append("\"");
                }

                // shape defaults to the 'type' column if not overridden by an attribute
                if (!graphvizAttrs.containsKey("shape")) {
                    sb.append(", shape=\"").append(type).append("\"");
                }

                // style=filled is needed for fillcolor to render
                if (graphvizAttrs.containsKey("fillcolor") && !graphvizAttrs.containsKey("style")) {
                    sb.append(", style=\"filled\"");
                }

                String dotLine = nodeId + " [" + sb + "];";
                System.out.println(dotLine);

                nodeObjects.add(new NodeObject(nodeId, dotLine, nodeDate));
            }

        } catch (IOException e) {
            System.err.println("IO error reading nodes file: " + e.getMessage());
        }
    }

    public void readTheRelsFile() {
        String nullsPath = filepath + stripExtension(relsFile) + "_Nulls.txt";

        // --- First pass: log missing node references ---
        try (var records = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new FileReader(filepath + relsFile));
             var writer = new FileWriter(nullsPath)) {

            for (CSVRecord record : records) {
                var dataRow = record.toMap();
                checkAndLog(dataRow.getOrDefault("match id 1", "").trim(), writer);
                checkAndLog(dataRow.getOrDefault("match id 2", "").trim(), writer);
            }
            System.out.println("Null-check written to: " + nullsPath);

        } catch (IOException e) {
            System.err.println("IO error during null-check pass: " + e.getMessage());
        }

        // --- Second pass: build edge statements ---
        try (var records = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new FileReader(filepath + relsFile))) {

            for (CSVRecord record : records) {
                var dataRow = record.toMap();

                String matchId1 = dataRow.getOrDefault("match id 1", "").trim();
                String matchId2 = dataRow.getOrDefault("match id 2", "").trim();
                String startId = nameToId.get(matchId1);
                String endId = nameToId.get(matchId2);

                if (startId == null || endId == null) {
                    System.err.println("Skipping edge – unresolved node(s): '" + matchId1 + "' / '" + matchId2 + "'");
                    continue;
                }

                markUsed(startId);
                markUsed(endId);

                // Collect edge attributes
                Map<String, String> edgeGraphviz = new LinkedHashMap<>();
                Map<String, String> edgeExtra = new LinkedHashMap<>();
                int maxN = maxAttributeN(dataRow);

                for (int n = 1; n <= maxN; n++) {
                    String attrLabel = dataRow.getOrDefault("attribute_label_" + n, "").trim();
                    String attrValue = dataRow.getOrDefault("value_" + n, "").trim();
                    if (attrLabel.isEmpty() || attrValue.isEmpty()) continue;
                    attrLabel = escapeString(attrLabel);
                    attrValue = escapeString(attrValue);
                    if (GRAPHVIZ_EDGE_ATTRS.contains(attrLabel)) {
                        edgeGraphviz.put(attrLabel, attrValue);
                    } else {
                        edgeExtra.put(attrLabel, attrValue);
                    }
                }

                var attrs = new StringBuilder();
                for (var e : edgeGraphviz.entrySet()) {
                    if (!attrs.isEmpty()) attrs.append(", ");
                    attrs.append(e.getKey()).append("=").append("\"").append(e.getValue()).append("\"");
                }
                if (!edgeExtra.isEmpty()) {
                    var tip = new StringBuilder();
                    for (var e : edgeExtra.entrySet()) {
                        if (!tip.isEmpty()) tip.append("&#10;");
                        tip.append(e.getKey()).append(": ").append(e.getValue());
                    }
                    if (!attrs.isEmpty()) attrs.append(", ");
                    attrs.append("tooltip=\"").append(tip).append("\"");
                }

                String edgeStmt = startId + " -> " + endId + (attrs.isEmpty() ? "" : " [" + attrs + "]") + ";";
                System.out.println(edgeStmt);
                edgeStatements.add(edgeStmt);
            }

        } catch (IOException e) {
            System.err.println("IO error reading rels file: " + e.getMessage());
        }
    }

    public void saveTheFile() {
        // Collect unique, sorted dates from used nodes
        List<LocalDate> rankDates = nodeObjects.stream()
                .filter(NodeObject::isUsed)
                .map(NodeObject::getDate)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        // Build timeline spine
        var rankLine = new StringBuilder();
        for (int i = 0; i < rankDates.size(); i++) {
            if (i > 0) rankLine.append(" -> ");
            rankLine.append("\"").append(rankDates.get(i)).append("\"");
        }
        if (!rankDates.isEmpty()) rankLine.append(";");

        // Build rank-same entries
        List<String> ranksList = nodeObjects.stream()
                .filter(NodeObject::isUsed)
                .filter(n -> n.getDate() != null)
                .map(n -> "{ rank = same; " + n.getNode() + "; \"" + n.getDate() + "\"; }")
                .toList();

        String outPath = filepath + stripExtension(relsFile) + ".dot";
        try (var writer = new FileWriter(outPath)) {
            writer.write("digraph G {\n");
            writer.write("\toverlap=false;\n");
            writer.write("\tsplines=true;\n");
            writer.write("\tranksep=.75;\n\n");

            // Timeline spine
            if (!rankLine.isEmpty()) {
                writer.write("\t{\n");
                writer.write("\t\tnode [shape=plaintext, fontsize=40];\n\n");
                writer.write("\t\t" + rankLine + "\n");
                writer.write("\t}\n\n");
            }

            for (var node : nodeObjects) {
                if (node.isUsed()) writer.write("\t" + node.getTheline() + "\n");
            }

            writer.write("\n");
            for (var rank : ranksList) writer.write("\t" + rank + "\n");
            writer.write("\n");
            for (var edge : edgeStatements) writer.write("\t" + edge + "\n");

            writer.write("}\n");
            System.out.println("DOT file written to: " + outPath);

        } catch (IOException e) {
            System.err.println("IO error writing DOT file: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Graphviz rendering
    // -------------------------------------------------------------------------

    /**
     * Checks whether the {@code dot} executable is available on the current PATH
     * by running {@code dot -V} and inspecting the exit code.
     *
     * @return true if dot is found and executable, false otherwise
     */
    public static boolean isDotAvailable() {
        try {
            var process = new ProcessBuilder("dot", "-V")
                    .redirectErrorStream(true)   // dot -V writes to stderr
                    .start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (IOException e) {
            // Executable not found on PATH
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Renders the DOT file produced by {@link #saveTheFile()} to a PNG using
     * the Graphviz {@code dot} command-line tool.
     *
     * <p>Checks for {@code dot} availability first; if not found, logs a clear
     * message and returns without throwing.
     *
     * @return the path of the generated PNG, or null if rendering was skipped or failed
     */
    public String renderToPng() {
        if (!isDotAvailable()) {
            System.err.println("Graphviz 'dot' not found on PATH – PNG not generated.");
            System.err.println("Install Graphviz from https://graphviz.org/download/ and ensure 'dot' is on your PATH.");
            return null;
        }

        String dotPath = filepath + stripExtension(relsFile) + ".dot";
        String pngPath = filepath + stripExtension(relsFile) + ".png";

        try {
            var process = new ProcessBuilder("dot", "-Tpng", dotPath, "-o", pngPath)
                    .redirectErrorStream(true)
                    .start();

            // Drain output so the process doesn't block on a full buffer
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();

            if (exit == 0) {
                System.out.println("PNG rendered to: " + pngPath);
                return pngPath;
            } else {
                System.err.println("dot exited with code " + exit + ". Output:\n" + output);
                return null;
            }

        } catch (IOException e) {
            System.err.println("IO error running dot: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("dot process interrupted.");
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getFilepath() { return filepath; }
    public void setFilepath(String filepath) { this.filepath = filepath; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void checkAndLog(String name, FileWriter writer) throws IOException {
        if (!name.isEmpty() && !nameToId.containsKey(name)) {
            writer.write(name + "\n");
        }
    }

    private void markUsed(String nodeId) {
        for (var n : nodeObjects) {
            if (n.getNode().equals(nodeId)) { n.setUsed(true); break; }
        }
    }

    private int maxAttributeN(Map<String, String> dataRow) {
        int max = 0;
        for (var key : dataRow.keySet()) {
            if (key.startsWith("attribute_label_")) {
                try {
                    int n = Integer.parseInt(key.substring("attribute_label_".length()));
                    if (n > max) max = n;
                } catch (NumberFormatException ignored) {}
            }
        }
        return max;
    }

    private String escapeString(String input) {
        return input.trim()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'");
    }

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DATE_FMT);
        } catch (DateTimeParseException e) {
            System.err.println("Invalid date format: " + dateStr);
            return null;
        }
    }

    /** Wraps long labels by replacing every 3rd space with a newline. */
    private String wrapLabel(String s) {
        var sb = new StringBuilder();
        int spaceCount = 0;
        for (char c : s.toCharArray()) {
            if (c == ' ' && ++spaceCount % 3 == 0) { sb.append('\n'); }
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    // -------------------------------------------------------------------------
    // Inner class
    // -------------------------------------------------------------------------

    private static final class NodeObject {
        private final String node;
        private final String theline;
        private final LocalDate date;
        private boolean used = false;

        NodeObject(String node, String theline, LocalDate date) {
            this.node = node;
            this.theline = theline;
            this.date = date;
        }

        public String getNode()    { return node; }
        public String getTheline() { return theline; }
        public LocalDate getDate() { return date; }
        public boolean isUsed()    { return used; }
        public void setUsed(boolean used) { this.used = used; }
    }
}