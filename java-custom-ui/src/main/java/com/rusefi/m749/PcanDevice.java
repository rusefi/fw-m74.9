package com.rusefi.m749;

import peak.can.MutableInteger;
import peak.can.basic.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class PcanDevice {
    static final class Channel {
        final TPCANHandle handle;
        final boolean available;
        /** True when the driver could not report the channel condition and the channel is only assumed to exist. */
        final boolean assumed;

        Channel(TPCANHandle handle, boolean available) {
            this(handle, available, false);
        }

        Channel(TPCANHandle handle, boolean available, boolean assumed) {
            this.handle = handle;
            this.available = available;
            this.assumed = assumed;
        }

        @Override
        public String toString() {
            return handle.name() + (available ? "" : " (in use)") + (assumed ? " (assumed)" : "");
        }
    }

    /** Sentinel that no real PCAN_CHANNEL_CONDITION value equals; it stays when the JNI layer drops the buffer. */
    private static final int CONDITION_NOT_WRITTEN = -1;

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }

    static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    static String driverHint() {
        return isMacOs()
                ? "Install MacCAN (brew tap mac-can/maccan && brew install pcbusb) and start Java with libpcanbasic_jni.dylib on java.library.path."
                : "Check the PCAN driver installation.";
    }

    private PCANBasic api;
    private boolean conditionUnsupported;

    /** Whether the last scan had to assume channels because the driver layer returns no channel condition. */
    boolean channelsAssumed() {
        return conditionUnsupported;
    }

    List<Channel> scan() throws IOException {
        if (api == null) {
            PCANBasic candidate = new PCANBasic();
            if (!candidate.initializeAPI()) {
                throw new IOException("Cannot initialize PCAN-Basic. " + driverHint());
            }
            api = candidate;
        }
        List<Channel> channels = new ArrayList<>();
        boolean unwritten = false;
        for (TPCANHandle handle : TPCANHandle.values()) {
            if (handle == TPCANHandle.PCAN_NONEBUS) {
                continue;
            }
            MutableInteger condition = new MutableInteger(CONDITION_NOT_WRITTEN);
            TPCANStatus status = api.GetValue(handle, TPCANParameter.PCAN_CHANNEL_CONDITION, condition, 4);
            if (status != TPCANStatus.PCAN_ERROR_OK) {
                continue;
            }
            if (condition.value == CONDITION_NOT_WRITTEN) {
                // The macOS bridge over MacCAN forwards the query but never copies the result back.
                unwritten = true;
                continue;
            }
            if ((condition.value & 3) != 0) {
                channels.add(new Channel(handle,
                        condition.value == TPCANParameterValue.PCAN_CHANNEL_AVAILABLE.getValue()));
            }
        }
        conditionUnsupported = channels.isEmpty() && unwritten;
        if (conditionUnsupported) {
            // Enumeration is impossible; expose the first USB channel like the upstream console does.
            // Whether an adapter is plugged in only shows when the channel is opened.
            channels.add(assumedChannel(TPCANHandle.PCAN_USBBUS1));
        }
        return channels;
    }

    /** An explicitly named USB channel that cannot be enumerated; opening it is the only presence check. */
    static Channel assumedChannel(TPCANHandle handle) {
        if (!handle.name().startsWith("PCAN_USBBUS")) {
            throw new IllegalArgumentException("Only PCAN-USB channels can be assumed: " + handle.name());
        }
        return new Channel(handle, true, true);
    }

    RawCanTransport open(Channel channel) throws IOException {
        TPCANStatus status = api.Initialize(channel.handle,
                TPCANBaudrate.PCAN_BAUD_500K, TPCANType.PCAN_TYPE_NONE, 0, (short) 0);
        if (status != TPCANStatus.PCAN_ERROR_OK) {
            throw new IOException("Open " + channel.handle + ": " + status
                    + (channel.assumed ? " (no adapter present on this assumed channel, or it is in use)" : ""));
        }
        return new RawCanTransport() {
            @Override
            public void sendCan(int id, byte[] frame) throws IOException {
                if (id < 0 || id > 0x7FF || frame.length > 8) {
                    throw new IOException("Invalid standard CAN frame");
                }
                requireOk("CAN write", api.Write(channel.handle, new TPCANMsg(id,
                        TPCANMessageType.PCAN_MESSAGE_STANDARD.getValue(), (byte) frame.length, frame)));
            }

            @Override
            public Frame receiveCan() throws IOException {
                // Bound each poll even on a saturated bus, so timeouts and cancellation still run.
                // MacCAN's CAN_Read never blocks; callers pause between empty polls.
                for (int i = 0; i < 64; i++) {
                    TPCANMsg message = new TPCANMsg();
                    TPCANStatus status = api.Read(channel.handle, message, null);
                    if (status == TPCANStatus.PCAN_ERROR_QRCVEMPTY) {
                        return null;
                    }
                    requireOk("CAN read", status);
                    if (message.getType() == TPCANMessageType.PCAN_MESSAGE_STANDARD.getValue()) {
                        int length = message.getLength() & 0xFF;
                        if (length > 8 || message.getData() == null || message.getData().length < length) {
                            throw new IOException("Invalid CAN frame length");
                        }
                        return new Frame(message.getID(), Arrays.copyOf(message.getData(), length));
                    }
                }
                return null;
            }

            @Override
            public void close() throws IOException {
                // Release only this channel, never channels owned by another console feature.
                // MacCAN is single-client: a skipped Uninitialize blocks every later Initialize in this process.
                requireOk("Close " + channel.handle, api.Uninitialize(channel.handle));
            }
        };
    }

    private static void requireOk(String operation, TPCANStatus status) throws IOException {
        if (status != TPCANStatus.PCAN_ERROR_OK) {
            throw new IOException(operation + ": " + status);
        }
    }
}
