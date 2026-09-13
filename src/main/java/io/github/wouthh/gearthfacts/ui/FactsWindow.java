package io.github.wouthh.gearthfacts.ui;

import io.github.wouthh.gearthfacts.runtime.PublisherSnapshot;
import io.github.wouthh.gearthfacts.runtime.Settings;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Small, headless-testable Swing view. Prefix edits are persisted immediately. */
public final class FactsWindow {
    private final JFrame frame = new JFrame("G-Earth Facts");
    private final JPasswordField apiKey = new JPasswordField(30);
    private final JTextField prefix = new JTextField(30);
    private final JButton save = new JButton("Save key");
    private final JButton start = new JButton("Start");
    private final JButton stop = new JButton("Stop");
    private final JLabel countdown = new JLabel("Next fact: —");
    private final JTextArea status = new JTextArea(6, 58);
    private final Consumer<Settings> saveSettings;
    private final Runnable startAction;
    private final Runnable stopAction;
    private final Consumer<String> prefixChanged;
    private boolean loading;
    private PublisherSnapshot snapshot = PublisherSnapshot.stopped("Stopped");
    private final Timer countdownTimer;

    public FactsWindow(
            Settings initial,
            Consumer<Settings> saveSettings,
            Runnable startAction,
            Runnable stopAction,
            Consumer<String> prefixChanged) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Swing construction requires EDT");
        this.saveSettings = Objects.requireNonNull(saveSettings);
        this.startAction = Objects.requireNonNull(startAction);
        this.stopAction = Objects.requireNonNull(stopAction);
        this.prefixChanged = Objects.requireNonNull(prefixChanged);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        FactsWindow.this.stopAction.run();
                    }

                    @Override
                    public void windowClosed(WindowEvent event) {
                        FactsWindow.this.stopAction.run();
                    }
                });
        loading = true;
        apiKey.setText(initial.apiKey());
        prefix.setText(initial.prefix());
        loading = false;
        build();
        prefix.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            public void insertUpdate(DocumentEvent e) {
                                changed();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                changed();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                changed();
                            }

                            private void changed() {
                                if (!loading)
                                    FactsWindow.this.prefixChanged.accept(prefix.getText());
                            }
                        });
        save.addActionListener(
                event ->
                        this.saveSettings.accept(
                                new Settings(new String(apiKey.getPassword()), prefix.getText())));
        start.addActionListener(event -> this.startAction.run());
        stop.addActionListener(event -> this.stopAction.run());
        countdownTimer = new Timer(1000, event -> refreshCountdown());
        countdownTimer.start();
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        fields.add(new JLabel("API Ninjas key"), c);
        c.gridx = 1;
        fields.add(apiKey, c);
        c.gridx = 0;
        c.gridy = 1;
        fields.add(new JLabel("Prefix"), c);
        c.gridx = 1;
        fields.add(prefix, c);
        c.gridx = 1;
        c.gridy = 2;
        fields.add(new JLabel("Prefix is saved as typed; default is empty."), c);
        root.add(fields, BorderLayout.NORTH);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(save);
        controls.add(start);
        controls.add(stop);
        controls.add(countdown);
        root.add(controls, BorderLayout.CENTER);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        root.add(new JScrollPane(status), BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(650, 285));
        frame.pack();
        frame.setLocationByPlatform(true);
    }

    public void show(PublisherSnapshot value) {
        update(value);
        frame.setVisible(true);
        frame.toFront();
    }

    public void update(PublisherSnapshot value) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Swing update requires EDT");
        snapshot = value;
        boolean running = value.running();
        start.setEnabled(!running);
        stop.setEnabled(running || value.part() > 0);
        status.setText(
                "State: "
                        + value.status()
                        + "\nOrigins room: "
                        + (value.roomId() > 0 ? value.roomId() : "unknown")
                        + "\nLast fact: "
                        + (value.lastFact().isEmpty() ? "—" : value.lastFact())
                        + "\nParts: "
                        + value.part()
                        + "/"
                        + value.parts());
        refreshCountdown();
    }

    private void refreshCountdown() {
        if (!SwingUtilities.isEventDispatchThread()) return;
        Instant at = snapshot.nextAt();
        if (at == null) countdown.setText("Next fact: —");
        else countdown.setText("Next fact: " + format(Duration.between(Instant.now(), at)));
    }

    private static String format(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    public String prefixText() {
        return prefix.getText();
    }

    public String apiKeyText() {
        return new String(apiKey.getPassword());
    }

    public void dispose() {
        countdownTimer.stop();
        frame.dispose();
    }
}
