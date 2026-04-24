package net.prgarnett;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * Swing GUI front-end for {@link DotMakeNodes}.
 *
 * Three actions, each with a dedicated button:
 *
 *  ┌─────────────────────────────────────────────────────┐
 *  │  Working directory   [path field]        [Browse…]  │
 *  │  Nodes CSV file      [path field]        [Browse…]  │
 *  │  Relationships CSV   [path field]        [Browse…]  │
 *  ├─────────────────────────────────────────────────────┤
 *  │  [Create DOT]  [Create DOT & PNG]  [Render Image]   │
 *  ├─────────────────────────────────────────────────────┤
 *  │  Console output                            [Clear]  │
 *  └─────────────────────────────────────────────────────┘
 *
 *  Create DOT         – writes the .dot file only (DotMakeNodes, unchanged)
 *  Create DOT & PNG   – writes .dot then renders via external dot command
 *                       (DotMakeNodes.renderToPng(), unchanged)
 *  Render Image       – writes .dot then renders via Java-native graphviz-java
 *                       (JavaRenderer, no external tool required)
 */
public class AppGui extends JFrame {

    // ── Input fields ──────────────────────────────────────────────────────────
    private final JTextField dirField   = new JTextField(40);
    private final JTextField nodesField = new JTextField(40);
    private final JTextField relsField  = new JTextField(40);

    // ── Console ───────────────────────────────────────────────────────────────
    private final JTextArea logArea   = new JTextArea();

    // ── Status bar ────────────────────────────────────────────────────────────
    private final JLabel    statusBar = new JLabel(" Ready");

    // ── Action buttons ────────────────────────────────────────────────────────
    private final JButton createDotButton    = new JButton("Create DOT");
    private final JButton dotAndPngButton    = new JButton("Create DOT & PNG");
    private final JButton renderImageButton  = new JButton("Render Image");

    // ─────────────────────────────────────────────────────────────────────────

    public AppGui() {
        super("Graph Dot File Generator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(700, 540));

        buildUi();
        redirectConsoleToLog();
        pack();
        setLocationRelativeTo(null);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.add(buildInputPanel());
        northStack.add(Box.createVerticalStrut(6));
        northStack.add(buildButtonPanel());

        root.add(northStack,       BorderLayout.NORTH);
        root.add(buildLogPanel(),  BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Input files"));

        GridBagConstraints lc = labelConstraints();
        GridBagConstraints fc = fieldConstraints();
        GridBagConstraints bc = buttonConstraints();

        panel.add(new JLabel("Working directory:"), lc(lc, 0));
        panel.add(dirField,                         fc(fc, 0));
        panel.add(browseButton("Choose directory", true,  dirField),  bc(bc, 0));

        panel.add(new JLabel("Nodes CSV file:"),    lc(lc, 1));
        panel.add(nodesField,                       fc(fc, 1));
        panel.add(browseButton("Choose file", false, nodesField),     bc(bc, 1));

        panel.add(new JLabel("Relationships CSV:"), lc(lc, 2));
        panel.add(relsField,                        fc(fc, 2));
        panel.add(browseButton("Choose file", false, relsField),      bc(bc, 2));

        return panel;
    }

    private JPanel buildButtonPanel() {
        // Tooltips make the distinction between the two PNG options clear
        createDotButton.setToolTipText("Parse CSVs and write the .dot file only");
        dotAndPngButton.setToolTipText("Write .dot then render PNG via the external 'dot' command (Graphviz must be installed)");
        renderImageButton.setToolTipText("Write .dot then render PNG via the built-in Java renderer (no external tool needed)");

        Font bold = createDotButton.getFont().deriveFont(Font.BOLD, 13f);
        createDotButton.setFont(bold);
        dotAndPngButton.setFont(bold);
        renderImageButton.setFont(bold);

        createDotButton.addActionListener(e   -> onRun(Action.DOT_ONLY));
        dotAndPngButton.addActionListener(e   -> onRun(Action.DOT_AND_PNG));
        renderImageButton.addActionListener(e -> onRun(Action.RENDER_IMAGE));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        panel.setBorder(new TitledBorder("Actions"));
        panel.add(createDotButton);
        panel.add(dotAndPngButton);
        panel.add(renderImageButton);
        return panel;
    }

    private JPanel buildLogPanel() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(200, 255, 200));
        logArea.setCaretColor(Color.WHITE);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(new TitledBorder("Console output"));
        scroll.setPreferredSize(new Dimension(680, 240));

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> logArea.setText(""));

        JPanel btnWrap = new JPanel(new BorderLayout());
        btnWrap.add(clearBtn, BorderLayout.NORTH);

        JPanel wrapper = new JPanel(new BorderLayout(4, 4));
        wrapper.add(scroll,  BorderLayout.CENTER);
        wrapper.add(btnWrap, BorderLayout.EAST);
        return wrapper;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEtchedBorder());
        statusBar.setFont(statusBar.getFont().deriveFont(Font.PLAIN, 11f));
        bar.add(statusBar, BorderLayout.WEST);
        return bar;
    }

    // ── Browse-button factory ─────────────────────────────────────────────────

    private JButton browseButton(String label, boolean dirOnly, JTextField target) {
        JButton btn = new JButton(label + "…");
        btn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();

            String current = target.getText().trim();
            if (!current.isEmpty()) {
                File f = new File(current);
                chooser.setCurrentDirectory(f.isDirectory() ? f : f.getParentFile());
            }

            if (dirOnly) {
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            } else {
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "CSV files (*.csv)", "csv"));
                String dir = dirField.getText().trim();
                if (!dir.isEmpty()) chooser.setCurrentDirectory(new File(dir));
            }

            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File chosen = chooser.getSelectedFile();
                if (dirOnly) {
                    target.setText(chosen.getAbsolutePath() + File.separator);
                } else {
                    // Store just the filename when it's inside the working directory
                    String dir = dirField.getText().trim();
                    if (!dir.isEmpty() && chosen.getParent().equals(
                            new File(dir).getAbsolutePath().replaceAll("[/\\\\]$", ""))) {
                        target.setText(chosen.getName());
                    } else {
                        target.setText(chosen.getAbsolutePath());
                    }
                }
            }
        });
        return btn;
    }

    // ── Action enum ───────────────────────────────────────────────────────────

    private enum Action {
        /** Parse CSVs and write the .dot file. */
        DOT_ONLY,
        /** Write .dot then render PNG via the external dot command. */
        DOT_AND_PNG,
        /** Write .dot then render PNG via the Java-native graphviz-java library. */
        RENDER_IMAGE
    }

    // ── Run action ────────────────────────────────────────────────────────────

    private void onRun(Action action) {
        String dir   = dirField.getText().trim();
        String nodes = nodesField.getText().trim();
        String rels  = relsField.getText().trim();

        if (dir.isEmpty() || nodes.isEmpty() || rels.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please fill in all three fields before running.",
                    "Missing input", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!dir.endsWith(File.separator)) dir += File.separator;
        final String finalDir = dir;

        setButtonsEnabled(false);
        setStatus("Running…");

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    log("─────────────────────────────────────────");
                    log("Working directory : " + finalDir);
                    log("Nodes file        : " + nodes);
                    log("Relationships file: " + rels);
                    log("Action            : " + actionLabel(action));
                    log("─────────────────────────────────────────");

                    // ── Step 1: always build the DOT file ────────────────────
                    DotMakeNodes processor = new DotMakeNodes(rels);
                    processor.setFilepath(finalDir);
                    processor.readTheNodesFile(nodes);
                    processor.readTheRelsFile();
                    processor.saveTheFile();

                    log("─────────────────────────────────────────");

                    // ── Step 2: optional PNG rendering ───────────────────────
                    switch (action) {

                        case DOT_ONLY -> {
                            log("Done.  DOT file written.");
                            return null;
                        }

                        case DOT_AND_PNG -> {
                            // Original DotMakeNodes behaviour — unchanged
                            String pngPath = processor.renderToPng();
                            log(pngPath != null
                                    ? "Done.  PNG written to: " + pngPath
                                    : "Done.  (PNG rendering failed — check console above)");
                            return null;
                        }

                        case RENDER_IMAGE -> {
                            // Java-native path via graphviz-java library
                            String pngPath = JavaRenderer.render(finalDir, rels);
                            log(pngPath != null
                                    ? "Done.  PNG written to: " + pngPath
                                    : "Done.  (Java rendering failed — check console above)");
                            return pngPath;
                        }
                    }

                } catch (Exception ex) {
                    log("ERROR: " + ex.getMessage());
                    ex.printStackTrace();
                }
                return null;
            }

            @Override
            protected void done() {
                setButtonsEnabled(true);
                setStatus("Finished — check console for details.");
                if (action == Action.RENDER_IMAGE) {
                    try {
                        String pngPath = get();
                        if (pngPath != null) showImageViewer(pngPath);
                    } catch (Exception ignored) {}
                }
            }
        };

        worker.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String actionLabel(Action action) {
        return switch (action) {
            case DOT_ONLY     -> "Create DOT only";
            case DOT_AND_PNG  -> "Create DOT & PNG  (external dot command)";
            case RENDER_IMAGE -> "Render Image  (Java-native graphviz-java)";
        };
    }

    // ── Image viewer ──────────────────────────────────────────────────────────

    /**
     * Opens a non-modal dialog showing the rendered PNG.
     *
     * The image is scaled down to fit the screen on open (preserving aspect
     * ratio). A scroll pane is also present so the user can scroll to examine
     * the full-resolution image by resizing the window.
     */
    private void showImageViewer(String pngPath) {
        SwingUtilities.invokeLater(() -> {
            BufferedImage img;
            try {
                img = ImageIO.read(new File(pngPath));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "Could not load image:\n" + ex.getMessage(),
                        "Image error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (img == null) {
                JOptionPane.showMessageDialog(this,
                        "Image file could not be decoded:\n" + pngPath,
                        "Image error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Scale to fit 80% of the screen, preserving aspect ratio
            Dimension screen   = Toolkit.getDefaultToolkit().getScreenSize();
            int maxW = (int) (screen.width  * 0.80);
            int maxH = (int) (screen.height * 0.80);

            int imgW = img.getWidth();
            int imgH = img.getHeight();
            double scale = Math.min(1.0,
                    Math.min((double) maxW / imgW, (double) maxH / imgH));
            int dispW = (int) (imgW * scale);
            int dispH = (int) (imgH * scale);

            Image scaled = img.getScaledInstance(dispW, dispH, Image.SCALE_SMOOTH);
            JLabel imageLabel = new JLabel(new ImageIcon(scaled));
            imageLabel.setHorizontalAlignment(SwingConstants.CENTER);

            // Scroll pane lets the user resize the window to see full resolution
            JScrollPane scroll = new JScrollPane(imageLabel,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scroll.getViewport().setBackground(Color.WHITE);
            scroll.setPreferredSize(new Dimension(
                    Math.min(dispW + 40, maxW),
                    Math.min(dispH + 40, maxH)));

            JDialog dialog = new JDialog(this, new File(pngPath).getName(), false);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.getContentPane().add(scroll);
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        });
    }

    private void setButtonsEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            createDotButton.setEnabled(enabled);
            dotAndPngButton.setEnabled(enabled);
            renderImageButton.setEnabled(enabled);
        });
    }

    private void redirectConsoleToLog() {
        PrintStream guiOut = new PrintStream(new TextAreaStream(logArea), true, StandardCharsets.UTF_8);
        System.setOut(guiOut);
        System.setErr(guiOut);
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusBar.setText(" " + text));
    }

    // ── GridBagConstraints helpers ────────────────────────────────────────────

    private static GridBagConstraints labelConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(6, 4, 6, 8);
        return c;
    }

    private static GridBagConstraints fieldConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.fill    = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets  = new Insets(6, 0, 6, 8);
        return c;
    }

    private static GridBagConstraints buttonConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(6, 0, 6, 4);
        return c;
    }

    private static GridBagConstraints lc(GridBagConstraints base, int row) {
        base.gridx = 0; base.gridy = row; return base;
    }
    private static GridBagConstraints fc(GridBagConstraints base, int row) {
        base.gridx = 1; base.gridy = row; return base;
    }
    private static GridBagConstraints bc(GridBagConstraints base, int row) {
        base.gridx = 2; base.gridy = row; return base;
    }

    // ── TextAreaStream inner class ────────────────────────────────────────────

    private static final class TextAreaStream extends OutputStream {
        private final JTextArea     area;
        private final StringBuilder buffer = new StringBuilder();

        TextAreaStream(JTextArea area) { this.area = area; }

        @Override
        public void write(int b) {
            buffer.append((char) b);
            if (b == '\n') flush();
        }

        @Override
        public void flush() {
            final String text = buffer.toString();
            buffer.setLength(0);
            SwingUtilities.invokeLater(() -> {
                area.append(text);
                area.setCaretPosition(area.getDocument().getLength());
            });
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new AppGui().setVisible(true));
    }
}