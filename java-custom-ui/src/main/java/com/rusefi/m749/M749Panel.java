package com.rusefi.m749;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class M749Panel extends JPanel {
    static final Color DETECTED_COLOR = new Color(0, 140, 45);
    static final Color MISSING_COLOR = new Color(190, 35, 35);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JLabel detection = new JLabel("PCAN not detected");
    private final JLabel detail = new JLabel("Scanning for PCAN adapters...");
    private final JLabel activity = new JLabel(" ");
    private final JButton retry = new JButton("Scan / query again");
    private final JTextArea status = new JTextArea();
    private final JTextArea messages = new JTextArea();
    private final M749Monitor.Backend backend;
    private ScheduledExecutorService worker;
    private M749Monitor monitor;
    private volatile int generation;

    public M749Panel() {
        this(M749Monitor.pcanBackend());
    }

    M749Panel(M749Monitor.Backend backend) {
        super(new BorderLayout(8, 8));
        this.backend = backend;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        detection.setForeground(MISSING_COLOR);
        detection.setFont(detection.getFont().deriveFont(Font.BOLD, 20f));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(detection);
        top.add(Box.createVerticalStrut(8));
        top.add(detail);
        top.add(Box.createVerticalStrut(12));
        top.add(new JLabel("M74.9 identification: VIN, IDs and metadata"));
        top.add(new JLabel("CAN: 500 kbit/s   Request: 0x7E0   Response: 0x7E8"));
        top.add(Box.createVerticalStrut(12));
        top.add(retry);
        top.add(Box.createVerticalStrut(8));
        top.add(activity);
        top.add(Box.createVerticalStrut(8));
        status.setName("status");
        status.setEditable(false);
        status.setOpaque(false);
        status.setFocusable(false);
        status.setFont(detail.getFont().deriveFont(Font.BOLD));
        top.add(status);

        messages.setName("messages");
        messages.setEditable(false);
        messages.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        messages.setRows(14);
        JTabbedPane lower = new JTabbedPane();
        lower.addTab("Messages", new JScrollPane(messages));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, lower);
        split.setResizeWeight(0.35);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        retry.addActionListener(event -> {
            if (worker != null) {
                retry.setEnabled(false);
                int current = generation;
                M749Monitor activeMonitor = monitor;
                worker.execute(() -> {
                    poll(activeMonitor, current, true);
                    onEdt(current, () -> retry.setEnabled(true));
                });
            }
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (worker != null) return;
        int current = ++generation;
        retry.setEnabled(true);
        activity.setText(" ");
        status.setText("");
        monitor = new M749Monitor(backend, new M749Monitor.View() {
            public void detection(boolean detected, String text) {
                onEdt(current, () -> {
                    detection.setText(detected ? "PCAN detected" : "PCAN not detected");
                    detection.setForeground(detected ? DETECTED_COLOR : MISSING_COLOR);
                    detail.setText(text);
                    detail.setToolTipText(text);
                });
            }

            public void identification(java.util.List<String> summary) {
                onEdt(current, () -> status.setText(String.join("\n", summary)));
            }

            public void message(String message) {
                onEdt(current, () -> appendMessage(message));
            }

            public void busy(boolean busy) {
                onEdt(current, () -> {
                    retry.setEnabled(!busy);
                    activity.setText(busy ? "Reading ECU identification..." : " ");
                });
            }
        });
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "m749-pcan");
            thread.setDaemon(true);
            return thread;
        });
        M749Monitor activeMonitor = monitor;
        worker.scheduleWithFixedDelay(() -> poll(activeMonitor, current, false), 0, 2, TimeUnit.SECONDS);
    }

    private void poll(M749Monitor activeMonitor, int current, boolean force) {
        // A removed/reinserted panel waits for the previous query to release its channel.
        synchronized (backend) {
            if (generation == current && !Thread.currentThread().isInterrupted()) {
                activeMonitor.poll(force);
            }
        }
    }

    @Override
    public void removeNotify() {
        generation++;
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        super.removeNotify();
    }

    private void onEdt(int current, Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (generation == current) action.run();
        });
    }

    private void appendMessage(String message) {
        messages.append("[" + TIME.format(LocalTime.now()) + "] " + message + "\n");
        // Keep a long-running console bounded, including repeated manual queries.
        if (messages.getDocument().getLength() > 200_000) {
            try {
                messages.getDocument().remove(0, 50_000);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
        messages.setCaretPosition(messages.getDocument().getLength());
    }
}
