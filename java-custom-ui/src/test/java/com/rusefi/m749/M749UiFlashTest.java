package com.rusefi.m749;

import com.rusefi.core.FindFileHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import peak.can.basic.TPCANHandle;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class M749UiFlashTest {
    @TempDir Path directory;

    private static final class Backend implements M749Monitor.Backend {
        final PcanDevice.Channel channel = new PcanDevice.Channel(TPCANHandle.PCAN_USBBUS2, true);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        volatile M749FirmwareDetection.Result firmware;
        volatile String software;
        volatile String part;
        volatile boolean fail;
        volatile boolean connected = true;
        volatile boolean inUpload;
        volatile M749Immo credential;
        volatile int uploads;
        volatile boolean readPair;
        volatile String[] transferArgs;

        Backend(M749FirmwareDetection.Result firmware) { this.firmware = firmware; }

        public List<PcanDevice.Channel> scan() {
            assertFalse(SwingUtilities.isEventDispatchThread());
            assertFalse(inUpload, "Scanning must not race an upload");
            return connected ? Collections.singletonList(channel) : Collections.emptyList();
        }

        public List<String> identify(PcanDevice.Channel channel, Consumer<String> messages) {
            return Collections.singletonList("ECU identification");
        }

        public M749Monitor.Identification inspect(PcanDevice.Channel channel, Consumer<String> messages) {
            assertFalse(SwingUtilities.isEventDispatchThread());
            return new M749Monitor.Identification(firmware, identify(channel, messages), software, part);
        }

        public void flash(PcanDevice.Channel selected, M749Image image, M749Immo credential, Consumer<String> messages)
                throws IOException, InterruptedException {
            assertFalse(SwingUtilities.isEventDispatchThread());
            assertEquals(channel.handle, selected.handle);
            assertEquals(M749Image.Domain.SOFTWARE, image.domain);
            image.requireActivationSupport();
            uploads++;
            this.credential = credential;
            inUpload = true;
            entered.countDown();
            try {
                assertTrue(proceed.await(10, TimeUnit.SECONDS));
                if (fail) throw new IOException("Activation status did not become ready", new IOException("fixture F1A0 NRC 31"));
                messages.accept("Upload complete: verified after reset");
            } finally {
                inUpload = false;
                released.countDown();
            }
        }

        public int transfer(String[] args, Consumer<String> messages) throws IOException {
            assertFalse(SwingUtilities.isEventDispatchThread());
            assertTrue(readPair, "Unexpected file transfer");
            transferArgs = args.clone();
            assertEquals("--read-pair", args[0]);
            M749PairFile saved = M749PairFile.load(Path.of(args[1]));
            for (int i = 0; i < M749PairFile.SIZE; i++) saved.put(i, i);
            saved.save(Path.of(args[1]));
            messages.accept("Pair file complete; no erase/download requests sent");
            return 0;
        }
    }

    @Test void oemInstallUsesNoCredentialAndShowsRusEfiOnlyAfterUploadCompletes() throws Exception {
        Backend backend = new Backend(M749FirmwareDetection.Result.OEM);
        M749Panel panel = open(backend, writeSoftware());
        try {
            awaitEdt(() -> text(panel, "firmwareStatus").equals("OEM firmware installed") && button(panel).isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                button(panel).doClick();
                assertFalse(button(panel).isEnabled());
                assertFalse(find(panel, JComboBox.class, "channels").isEnabled());
                assertFalse(find(panel, JComboBox.class, "transferTransport").isEnabled());
            });
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            assertNull(backend.credential);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("OEM firmware installed", text(panel, "firmwareStatus"));
                assertFalse(find(panel, JTextArea.class, "status").getText().contains("Upload complete"));
            });
            backend.proceed.countDown();
            awaitEdt(() -> text(panel, "activity").equals("Upload complete") && button(panel).isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Update rusEFI", button(panel).getText());
                assertTrue(text(panel, "firmwareStatus").startsWith("rusEFI installed"));
                assertTrue(find(panel, JTextArea.class, "messages").getText().contains("verified after reset"));
            });
            assertEquals(1, backend.uploads);
        } finally { close(panel, backend); }
    }

    @Test void pairedI865AutomaticallyUsesLocalPairFile() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", directory.toString());
            M749PairFile pair = new M749PairFile();
            for (int i = 0; i < M749PairFile.SIZE; i++) pair.put(i, i);
            Path pairPath = M749AutoCredential.path();
            Files.createDirectories(pairPath.getParent());
            pair.save(pairPath);
            Backend backend = new Backend(M749FirmwareDetection.Result.OEM);
            backend.software = "I865LB52_w2404b1";
            backend.part = "8450094615";
            M749Panel panel = open(backend, writeSoftware());
            try {
                awaitEdt(() -> text(panel, "firmwareStatus").equals("OEM firmware installed") && button(panel).isEnabled());
                SwingUtilities.invokeAndWait(() -> button(panel).doClick());
                assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
                assertNotNull(backend.credential);
                assertEquals(0, backend.credential.pairFile().get(0));
                assertEquals(23, backend.credential.pairFile().get(23));
                backend.proceed.countDown();
                awaitEdt(() -> text(panel, "activity").equals("Upload complete"));
            } finally { close(panel, backend); }
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test void pairedI865PopulatesSparseCacheFromLiveReadBeforeUpload() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", directory.toString());
            Path pairPath = M749AutoCredential.path();
            Files.createDirectories(pairPath.getParent());
            M749PairFile partial = new M749PairFile();
            partial.put(0, 0);
            partial.save(pairPath);
            Backend backend = new Backend(M749FirmwareDetection.Result.OEM);
            backend.software = "I865LB52_w2404b1";
            backend.part = "8450094615";
            backend.readPair = true;
            M749Panel panel = open(backend, writeSoftware());
            try (M749LogCapture saved = new M749LogCapture(directory.resolve("live-pair.log"))) {
                awaitEdt(() -> text(panel, "firmwareStatus").equals("OEM firmware installed") && button(panel).isEnabled());
                SwingUtilities.invokeAndWait(() -> button(panel).doClick());
                assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
                assertNotNull(backend.credential);
                assertEquals(pairPath.toString(), backend.transferArgs[1]);
                assertEquals("--channel", backend.transferArgs[2]);
                assertEquals(24, M749PairFile.load(pairPath).knownCount());
                saved.await("populated local file " + pairPath);
                assertTrue(saved.text().contains("incomplete at " + pairPath));
                SwingUtilities.invokeAndWait(() -> assertTrue(find(panel, JTextArea.class, "messages")
                        .getText().contains("populated local file " + pairPath)));
                backend.proceed.countDown();
                awaitEdt(() -> text(panel, "activity").equals("Upload complete"));
            } finally { close(panel, backend); }
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test void rusEfiUpdateNeedsNoCredentialAndFailureDoesNotClaimInstallation() throws Exception {
        Backend backend = new Backend(M749FirmwareDetection.Result.M749_READY);
        backend.fail = true;
        M749Panel panel = open(backend, writeSoftware());
        try (M749LogCapture saved = new M749LogCapture(directory.resolve("failure.log"))) {
            awaitEdt(() -> button(panel).isEnabled() && button(panel).getText().equals("Update rusEFI"));
            SwingUtilities.invokeAndWait(() -> button(panel).doClick());
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            assertNull(backend.credential);
            backend.proceed.countDown();
            awaitEdt(() -> text(panel, "activity").startsWith("Upload failed") && !button(panel).isEnabled());
            saved.await("Upload failed: Activation status did not become ready");
            assertTrue(saved.text().contains("M749 build:"));
            assertTrue(saved.text().contains("PCAN_USBBUS2"));
            assertTrue(saved.text().contains("Caused by: java.io.IOException: fixture F1A0 NRC 31"));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Installed firmware: unknown", text(panel, "firmwareStatus"));
                assertFalse(find(panel, JTextArea.class, "messages").getText().contains("Upload complete"));
            });
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(text(panel, "activity").contains("Scan / query again"));
                button(panel).doClick(); // A disabled retry must not enqueue another upload.
                find(panel, JButton.class, "retry").doClick();
            });
            awaitEdt(() -> button(panel).isEnabled());
            assertEquals(1, backend.uploads);
        } finally { close(panel, backend); }
    }

    @Test void invalidImageAndIncompletePairNeverReachUploader() throws Exception {
        Backend backend = new Backend(M749FirmwareDetection.Result.OEM);
        Path bad = directory.resolve("bad.srec");
        Files.writeString(bad, "S70508080001E9\n");
        M749Panel panel = open(backend, bad);
        try {
            awaitEdt(() -> button(panel).isEnabled());
            SwingUtilities.invokeAndWait(() -> button(panel).doClick());
            awaitEdt(() -> text(panel, "activity").startsWith("Upload failed"));
            assertEquals(0, backend.uploads);
        } finally { close(panel, backend); }

        Path pair = directory.resolve("incomplete.pair");
        new M749PairFile().save(pair);
        M749Monitor monitor = new M749Monitor(backend, new SilentView());
        assertThrows(IOException.class, () -> monitor.flash(backend.channel, writeSoftware(), pair, message -> { }));
        assertEquals(0, backend.uploads);
    }

    @Test void genericRusEfiIsLabeledButCannotUseUnconfirmedM749UpdateProtocol() throws Exception {
        Backend backend = new Backend(M749FirmwareDetection.Result.RUSEFI);
        M749Panel panel = open(backend, writeSoftware());
        try {
            awaitEdt(() -> text(panel, "firmwareStatus").contains("update support not confirmed"));
            SwingUtilities.invokeAndWait(() -> assertFalse(button(panel).isEnabled()));
            assertEquals(0, backend.uploads);
        } finally { close(panel, backend); }
    }

    @Test void disconnectClearsInstalledLabelAndDisablesUpload() throws Exception {
        Backend backend = new Backend(M749FirmwareDetection.Result.M749_READY);
        M749Panel panel = open(backend, writeSoftware());
        try {
            awaitEdt(() -> text(panel, "firmwareStatus").startsWith("rusEFI installed"));
            backend.connected = false;
            awaitEdt(() -> text(panel, "firmwareStatus").equals("Installed firmware: unknown") && !button(panel).isEnabled());
        } finally { close(panel, backend); }
    }

    @Test void removingPanelInterruptsUploadAndReleasesWorker() throws Exception {
        Backend backend = new Backend(M749FirmwareDetection.Result.M749_READY);
        M749Panel panel = open(backend, writeSoftware());
        awaitEdt(() -> button(panel).isEnabled());
        SwingUtilities.invokeAndWait(() -> button(panel).doClick());
        assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
        try (M749LogCapture saved = new M749LogCapture(directory.resolve("removed.log"))) {
            SwingUtilities.invokeAndWait(panel::removeNotify);
            assertTrue(backend.released.await(5, TimeUnit.SECONDS));
            saved.await("Upload interrupted; check ECU state before retrying.");
            assertEquals(1, backend.uploads);
        }
    }

    @Test void updaterDiscoverySelectsThisBoardsNamedSrec() throws Exception {
        String previous = FindFileHelper.INPUT_FILES_PATH;
        try {
            FindFileHelper.INPUT_FILES_PATH = directory.toString();
            Path expected = directory.resolve("rusefi_main_2026-09-22_re74.9_123_abc_update.srec");
            Files.writeString(expected, "fixture");
            Files.writeString(directory.resolve("rusefi_main_2026-09-22_other_123_def_update.srec"), "other board");
            assertEquals(expected, M749FirmwareFile.locate());
        } finally { FindFileHelper.INPUT_FILES_PATH = previous; }
    }

    private M749Panel open(Backend backend, Path image) throws Exception {
        AtomicReference<M749Panel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            M749Panel panel = new M749Panel(backend, () -> image);
            reference.set(panel);
            find(panel, JComboBox.class, "transferTransport").setSelectedIndex(0);
            panel.addNotify();
        });
        return reference.get();
    }

    private static void close(M749Panel panel, Backend backend) throws Exception {
        SwingUtilities.invokeAndWait(panel::removeNotify);
        backend.proceed.countDown();
    }

    private static JButton button(M749Panel panel) { return find(panel, JButton.class, "flash"); }
    private static String text(M749Panel panel, String name) { return find(panel, JLabel.class, name).getText(); }

    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(10);
        }
        fail("UI condition did not become true");
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) {
                T found = find((Container) child, type, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class SilentView implements M749Monitor.View {
        public void detection(boolean detected, String detail) { }
        public void identification(List<String> summary) { }
        public void message(String message) { }
        public void busy(boolean busy) { }
    }

    private Path writeSoftware() throws IOException {
        return writeSoftware(directory);
    }

    static Path writeSoftware(Path directory) throws IOException {
        StringBuilder output = new StringBuilder();
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (com.rusefi.libopenblt.file.SrecParser.SRecord range : M749ImageTest.records(M749Image.Domain.SOFTWARE)) {
            for (int offset = 0; offset < range.data.length; offset += 32) {
                byte[] row = new byte[38];
                row[0] = 37;
                int address = range.address + offset;
                for (int i = 0; i < 4; i++) row[i + 1] = (byte) (address >>> (24 - i * 8));
                System.arraycopy(range.data, offset, row, 5, 32);
                int sum = 0;
                for (int i = 0; i < 37; i++) sum += row[i] & 255;
                row[37] = (byte) ~sum;
                output.append("S3");
                for (byte value : row) output.append(hex[(value & 255) >>> 4]).append(hex[value & 15]);
                output.append('\n');
            }
        }
        Path path = directory.resolve("firmware.srec");
        Files.writeString(path, output.append("S70508080001E9\n"));
        return path;
    }
}
