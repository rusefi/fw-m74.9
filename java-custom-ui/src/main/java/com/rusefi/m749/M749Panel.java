package com.rusefi.m749;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
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
    private final JLabel firmwareStatus = new JLabel("Installed firmware: unknown");
    private final JLabel imageLabel = new JLabel("SREC: searching...");
    private final JLabel uploadHint = new JLabel();
    private final JComboBox<PcanDevice.Channel> channels = new JComboBox<>();
    private final JTextField credential = new JTextField(28);
    private final JButton browseCredential = new JButton("Choose pair file / backup...");
    private final JButton flash = new JButton("Flash rusEFI");
    private final JTextArea status = new JTextArea();
    private final JTextArea messages = new JTextArea();
    private final M749Monitor.Backend backend;
    private final M749FirmwareFile.Locator firmwareLocator;
    private Path imagePath;
    private M749FirmwareDetection.Result installed = M749FirmwareDetection.Result.UNKNOWN;
    private boolean changingChannels;
    private boolean querying;
    private volatile boolean uploading;
    private volatile boolean autoSelect = true;
    private volatile String selectedChannel;
    private ScheduledExecutorService worker;
    private M749Monitor monitor;
    private volatile int generation;

    public M749Panel() {
        this(M749Monitor.pcanBackend());
    }

    M749Panel(M749Monitor.Backend backend) {
        this(backend, M749FirmwareFile::locate);
    }

    M749Panel(M749Monitor.Backend backend, M749FirmwareFile.Locator firmwareLocator) {
        super(new BorderLayout(8, 8));
        this.backend = backend;
        this.firmwareLocator = firmwareLocator;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        detection.setForeground(MISSING_COLOR);
        detection.setFont(detection.getFont().deriveFont(Font.BOLD, 20f));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(detection);
        top.add(Box.createVerticalStrut(8));
        top.add(detail);
        top.add(Box.createVerticalStrut(12));
        top.add(new JLabel("M74.9 identification and firmware update"));
        top.add(new JLabel("CAN: 500 kbit/s   Request: 0x7E0   Response: 0x7E8"));
        top.add(Box.createVerticalStrut(12));
        JPanel adapterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        adapterRow.add(new JLabel("PCAN channel: "));
        channels.setName("channels");
        adapterRow.add(channels);
        adapterRow.add(Box.createHorizontalStrut(8));
        adapterRow.add(retry);
        adapterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, adapterRow.getPreferredSize().height));
        top.add(adapterRow);
        firmwareStatus.setName("firmwareStatus");
        firmwareStatus.setFont(detail.getFont().deriveFont(Font.BOLD));
        top.add(firmwareStatus);
        imageLabel.setName("firmwareImage");
        top.add(imageLabel);
        JPanel credentialRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        credentialRow.add(new JLabel("OEM credentials (optional): "));
        credential.setName("credential");
        credential.setToolTipText("Pair file or original paired backup for OEM installation. Not needed for a rusEFI update.");
        credentialRow.add(credential);
        credentialRow.add(Box.createHorizontalStrut(8));
        credentialRow.add(browseCredential);
        credentialRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, credentialRow.getPreferredSize().height));
        top.add(credentialRow);
        top.add(uploadHint);
        flash.setName("flash");
        flash.setEnabled(false);
        top.add(flash);
        top.add(Box.createVerticalStrut(8));
        activity.setName("activity");
        top.add(activity);
        top.add(Box.createVerticalStrut(8));
        status.setName("status");
        status.setEditable(false);
        status.setOpaque(false);
        status.setFocusable(false);
        status.setFont(detail.getFont().deriveFont(Font.BOLD));
        top.add(status);
        for (Component component : top.getComponents()) {
            if (component instanceof JComponent) {
                ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
            }
        }

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
        retry.addActionListener(event -> queryAgain());
        channels.addActionListener(event -> {
            if (changingChannels) return;
            PcanDevice.Channel channel = (PcanDevice.Channel) channels.getSelectedItem();
            selectedChannel = channel == null ? null : channel.handle.name();
            autoSelect = false;
            setFirmware(M749FirmwareDetection.Result.UNKNOWN);
            status.setText("");
            queryAgain();
        });
        browseCredential.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("ECU pair file or original backup", "pair", "bin"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                credential.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        flash.addActionListener(event -> flashFirmware());
    }

    private void queryAgain() {
        if (worker == null || uploading || querying) return;
        querying = true;
        updateControls();
        int current = generation;
        M749Monitor activeMonitor = monitor;
        worker.execute(() -> {
            refreshFirmware(current);
            poll(activeMonitor, current, true);
            onEdt(current, () -> { querying = false; updateControls(); });
        });
    }

    private void refreshFirmware(int current) {
        try {
            Path path = firmwareLocator.locate();
            onEdt(current, () -> {
                imagePath = path;
                imageLabel.setText("SREC: " + path.getFileName());
                imageLabel.setToolTipText(path.toString());
                appendMessage("Firmware image: " + path);
                updateControls();
            });
        } catch (Exception e) {
            onEdt(current, () -> {
                imagePath = null;
                imageLabel.setText("SREC unavailable: " + e.getMessage());
                imageLabel.setToolTipText(null);
                updateControls();
            });
        }
    }

    private void showChannels(List<PcanDevice.Channel> available) {
        boolean unchanged = channels.getItemCount() == available.size();
        for (int i = 0; unchanged && i < available.size(); i++) {
            PcanDevice.Channel previous = channels.getItemAt(i);
            PcanDevice.Channel next = available.get(i);
            unchanged = previous.handle == next.handle && previous.available == next.available;
        }
        if (unchanged) return;
        changingChannels = true;
        try {
            String previous = selectedChannel;
            channels.removeAllItems();
            PcanDevice.Channel selection = null;
            for (PcanDevice.Channel channel : available) {
                channels.addItem(channel);
                if (channel.handle.name().equals(previous)) selection = channel;
            }
            // Do not switch a chosen upload target to a different adapter on disconnect.
            if (selection == null && autoSelect) {
                selection = available.stream().filter(c -> c.available).findFirst().orElse(null);
            }
            channels.setSelectedItem(selection);
            selectedChannel = selection == null ? previous : selection.handle.name();
            if (selection == null || !selection.available || !selection.handle.name().equals(previous)) {
                setFirmware(M749FirmwareDetection.Result.UNKNOWN);
                status.setText("");
            }
        } finally {
            changingChannels = false;
        }
        updateControls();
    }

    private void setFirmware(M749FirmwareDetection.Result result) {
        installed = result;
        uploadHint.setText(result.m749 ? "rusEFI updates use the resident loader; no pair file or startup power cycle is needed."
                : "For OEM authorization, choose your pair file and cycle ECU power when Messages asks.");
        String label = result == M749FirmwareDetection.Result.UNKNOWN ? "Installed firmware: unknown"
                : result == M749FirmwareDetection.Result.OEM ? "OEM firmware installed"
                : "rusEFI installed - " + (result == M749FirmwareDetection.Result.M749_READY ? "ready to update"
                : result == M749FirmwareDetection.Result.M749_NOT_READY ? "activation not ready"
                : "M74.9 update support not confirmed");
        firmwareStatus.setText(label);
        firmwareStatus.setForeground(result.m749 ? DETECTED_COLOR : getForeground());
        flash.setText(result.m749 ? "Update rusEFI" : "Flash rusEFI");
        updateControls();
    }

    private void updateControls() {
        boolean idle = worker != null && !uploading && !querying;
        retry.setEnabled(idle);
        channels.setEnabled(idle);
        credential.setEnabled(idle && !installed.m749);
        browseCredential.setEnabled(idle && !installed.m749);
        PcanDevice.Channel channel = (PcanDevice.Channel) channels.getSelectedItem();
        flash.setEnabled(idle && imagePath != null && channel != null && channel.available
                && installed != M749FirmwareDetection.Result.RUSEFI);
    }

    private void flashFirmware() {
        if (!flash.isEnabled()) return;
        PcanDevice.Channel channel = (PcanDevice.Channel) channels.getSelectedItem();
        Path image = imagePath;
        String credentialText = installed.m749 ? "" : credential.getText().trim();
        uploading = true;
        updateControls();
        activity.setText("Flashing firmware - keep ECU power and PCAN connected.");
        int current = generation;
        M749Monitor activeMonitor = monitor;
        long start = System.nanoTime();
        Consumer<String> log = message -> {
            String line = String.format(java.util.Locale.ROOT, "[%4d] %s",
                    (System.nanoTime() - start) / 1_000_000_000L, message);
            onEdt(current, () -> appendMessage(line));
        };
        worker.execute(() -> {
            synchronized (backend) {
                try {
                    if (generation != current || Thread.currentThread().isInterrupted()) return;
                    log.accept("Flashing " + image + " on " + channel.handle);
                    activeMonitor.flash(channel, image, credentialText.isEmpty() ? null : Path.of(credentialText), log);
                    onEdt(current, () -> {
                        setFirmware(M749FirmwareDetection.Result.M749_READY);
                        status.setText("Upload complete: CRCs and boot marker verified after reset.");
                        activity.setText("Upload complete");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.accept("Upload interrupted; check ECU state before retrying.");
                    onEdt(current, () -> uploadFailed("Upload interrupted"));
                } catch (Exception | LinkageError e) {
                    log.accept("Upload failed: " + e.getMessage());
                    onEdt(current, () -> uploadFailed("Upload failed: " + e.getMessage()));
                } finally {
                    onEdt(current, () -> { uploading = false; updateControls(); });
                }
            }
        });
    }

    private void uploadFailed(String message) {
        setFirmware(M749FirmwareDetection.Result.UNKNOWN);
        status.setText("");
        activity.setText(message);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (worker != null) return;
        int current = ++generation;
        querying = false;
        uploading = false;
        setFirmware(M749FirmwareDetection.Result.UNKNOWN);
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

            public void identification(PcanDevice.Channel channel, List<String> summary) {
                onEdt(current, () -> {
                    if (!uploading && (autoSelect || channel.handle.name().equals(selectedChannel))) {
                        status.setText(String.join("\n", summary));
                    }
                });
            }

            public void channels(List<PcanDevice.Channel> available) {
                onEdt(current, () -> showChannels(available));
            }

            public void firmware(PcanDevice.Channel channel, M749FirmwareDetection.Result result) {
                onEdt(current, () -> {
                    if (uploading || (!autoSelect && !channel.handle.name().equals(selectedChannel))) return;
                    if (result != M749FirmwareDetection.Result.UNKNOWN) {
                        changingChannels = true;
                        for (int i = 0; i < channels.getItemCount(); i++) {
                            if (channels.getItemAt(i).handle == channel.handle) channels.setSelectedIndex(i);
                        }
                        changingChannels = false;
                        selectedChannel = channel.handle.name();
                        autoSelect = false;
                    }
                    setFirmware(result);
                });
            }

            public void message(String message) {
                onEdt(current, () -> appendMessage(message));
            }

            public void busy(boolean busy) {
                onEdt(current, () -> {
                    querying = busy;
                    updateControls();
                    if (!uploading) activity.setText(busy ? "Reading ECU identification..." : " ");
                });
            }
        });
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "m749-pcan");
            thread.setDaemon(true);
            return thread;
        });
        M749Monitor activeMonitor = monitor;
        worker.execute(() -> refreshFirmware(current));
        worker.scheduleWithFixedDelay(() -> poll(activeMonitor, current, false), 0, 2, TimeUnit.SECONDS);
        updateControls();
    }

    private void poll(M749Monitor activeMonitor, int current, boolean force) {
        // A removed/reinserted panel waits for the previous query to release its channel.
        synchronized (backend) {
            if (generation == current && !uploading && !Thread.currentThread().isInterrupted()) {
                activeMonitor.poll(force, autoSelect ? null : selectedChannel == null ? "" : selectedChannel);
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
        updateControls();
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
