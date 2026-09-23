package com.rusefi.m749;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** All methods run on one worker; callbacks are marshalled to Swing by the panel. */
final class M749Monitor {
    interface Backend {
        List<PcanDevice.Channel> scan() throws IOException;
        /** @return human-readable status lines for the queried ECU */
        List<String> identify(PcanDevice.Channel channel, Consumer<String> messages) throws IOException, InterruptedException;
    }

    interface View {
        void detection(boolean detected, String detail);
        void identification(List<String> summary);
        void message(String message);
        void busy(boolean busy);
    }

    private final Backend backend;
    private final View view;
    private final Set<String> attempted = new HashSet<>();
    private String previousScan = "";

    M749Monitor(Backend backend, View view) {
        this.backend = backend;
        this.view = view;
    }

    static Backend pcanBackend() {
        PcanDevice device = new PcanDevice();
        return new Backend() {
            public List<PcanDevice.Channel> scan() throws IOException {
                return device.scan();
            }

            public List<String> identify(PcanDevice.Channel channel, Consumer<String> messages)
                    throws IOException, InterruptedException {
                try (DiagnosticTransport transport = device.open(channel)) {
                    M749FirmwareDetection.Result firmware = M749FirmwareDetection.detect(new UdsClient(transport));
                    if (firmware != M749FirmwareDetection.Result.UNKNOWN) {
                        return java.util.Collections.singletonList(firmware.description);
                    }
                    return M749Identification.summarize(new M749Identification(transport, messages).run());
                }
            }
        };
    }

    void poll(boolean retry) {
        List<PcanDevice.Channel> channels;
        try {
            channels = backend.scan();
        } catch (IOException | RuntimeException | LinkageError e) {
            String error = e instanceof LinkageError
                    ? "PCAN native library unavailable. Install the PCAN driver and matching PCAN-Basic/JNI libraries, then restart."
                    : "PCAN scan failed: " + e.getMessage();
            view.detection(false, error);
            reportScan(error);
            // An API error is not proof of unplugging: do not reauthenticate automatically.
            return;
        }
        Set<String> present = new HashSet<>();
        for (PcanDevice.Channel channel : channels) present.add(channel.handle.name());
        attempted.retainAll(present);
        String detail = channels.isEmpty() ? "Connect a PCAN adapter to begin." : channels.toString();
        view.detection(!channels.isEmpty(), detail);
        reportScan(channels.isEmpty() ? "PCAN not detected" : "PCAN detected: " + detail);
        List<PcanDevice.Channel> candidates = new ArrayList<>();
        for (PcanDevice.Channel channel : channels) {
            if (channel.available && (retry || !attempted.contains(channel.handle.name()))) {
                candidates.add(channel);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        view.busy(true);
        try {
            // The driver can report phantom channels (e.g. ISA with no driver), so
            // keep trying until one yields an ECU.
            for (PcanDevice.Channel selected : candidates) {
                attempted.add(selected.handle.name());
                view.message("Querying M74.9 via " + selected.handle + " at 500 kbit/s (7E0 / 7E8)");
                try {
                    view.identification(backend.identify(selected, view::message));
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException | RuntimeException | LinkageError e) {
                    view.message("Identification via " + selected.handle + " failed: " + e.getMessage());
                }
            }
            view.message("No channel produced an M74.9 identification. Use Scan / query again to retry.");
        } finally {
            view.busy(false);
        }
    }

    private void reportScan(String message) {
        if (!message.equals(previousScan)) {
            view.message(message);
            previousScan = message;
        }
    }
}
