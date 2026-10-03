package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import peak.can.basic.TPCANHandle;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class PcanDeviceTest {
    @Test
    public void assumedChannelIsUsbOnlyAndLabeled() {
        PcanDevice.Channel channel = PcanDevice.assumedChannel(TPCANHandle.PCAN_USBBUS3);
        assertTrue(channel.available);
        assertTrue(channel.assumed);
        assertEquals("PCAN_USBBUS3 (assumed)", channel.toString());
        assertEquals("PCAN_USBBUS2", new PcanDevice.Channel(TPCANHandle.PCAN_USBBUS2, true).toString());
        assertEquals("PCAN_USBBUS2 (in use)", new PcanDevice.Channel(TPCANHandle.PCAN_USBBUS2, false).toString());
        assertThrows(IllegalArgumentException.class, () -> PcanDevice.assumedChannel(TPCANHandle.PCAN_PCIBUS1));
    }

    @Test
    public void nativeLibraryHintNamesThePlatformRemedy() {
        String hint = M749Cli.nativeLibraryHint();
        if (PcanDevice.isLinux()) {
            assertTrue(hint.contains("--socketcan"), hint);
        } else if (PcanDevice.isMacOs()) {
            assertTrue(hint.contains("MacCAN") && hint.contains("java.library.path"), hint);
        } else {
            assertTrue(hint.contains("PEAK"), hint);
        }
        assertFalse(PcanDevice.isLinux() && PcanDevice.isMacOs());
    }

    @Test
    public void linuxRejectsPcanBeforeTouchingTheDriver() throws IOException {
        if (!PcanDevice.isLinux()) {
            return;
        }
        M749ConnectionOptions options = new M749ConnectionOptions();
        options.accept("--channel", "PCAN_USBBUS1");
        List<String> messages = new java.util.ArrayList<>();
        Consumer<String> out = messages::add;
        IOException e = assertThrows(IOException.class, () -> M749ConnectionOptions.open(options, out));
        assertTrue(e.getMessage().contains("macOS") && e.getMessage().contains("--socketcan"), e.getMessage());
        assertTrue(messages.isEmpty());
    }
}
