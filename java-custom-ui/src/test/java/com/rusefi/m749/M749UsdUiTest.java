package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import peak.can.basic.TPCANHandle;
import static org.junit.jupiter.api.Assertions.*;

class M749UsdUiTest {
    @TempDir Path directory;

    @Test void inspectInExistingFolderSavesIntoNewChildAndPreservesOtherFiles() throws Exception {
        Path existing = directory.resolve("usd-before.bin");
        Files.write(existing, new byte[]{1, 2, 3});
        Path destination = M749Panel.newUsdInspectionDirectory(directory);
        assertEquals(directory, destination.getParent());
        assertFalse(Files.exists(destination));
        M749UsdPatcherTest.Ecu ecu = new M749UsdPatcherTest.Ecu(); ecu.output = destination;
        assertEquals(0, M749UsdPatchCli.execute(new String[]{"--inspect-usd", destination.toString(),
                        "--socketcan", "can0", "--tx-gap", "0"},
                o -> new M749ReadFlashCliTest.WireEcu(ecu), s -> {}));
        assertArrayEquals(ecu.original, M749UsdPatcher.readInspection(destination));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(existing));
        assertFalse(Files.exists(destination.resolve(M749UsdPatcher.ATTEMPT_FILE)));
        assertEquals(1, ecu.resetCount);
        assertThrows(IOException.class, () -> M749Panel.newUsdInspectionDirectory(existing));
        assertThrows(IOException.class, () -> M749Panel.newUsdInspectionDirectory(directory.resolve("missing")));
    }

    static class Backend implements M749Monitor.Backend {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile List<String> args;
        volatile boolean active;
        int result;
        public List<PcanDevice.Channel> scan() {
            assertFalse(active);
            return List.of(new PcanDevice.Channel(TPCANHandle.PCAN_USBBUS2, true));
        }
        public List<String> identify(PcanDevice.Channel channel, Consumer<String> out) { return List.of("OEM ECU"); }
        public int transfer(String[] args, Consumer<String> out) throws InterruptedException {
            assertFalse(SwingUtilities.isEventDispatchThread());
            this.args = List.of(args); active = true; entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); return result; }
            finally { active = false; }
        }
    }

    @Test void allMaintenanceActionsUseSelectedConnectionAndSuspendOtherTransfers() throws Exception {
        for (M749Panel.UsdAction action : M749Panel.UsdAction.values()) {
            Backend backend = new Backend(); backend.result = action == M749Panel.UsdAction.APPLY ? 3 : 0;
            AtomicReference<M749Panel> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                M749Panel panel = new M749Panel(backend, () -> { throw new IOException("No firmware"); },
                        (parent, read) -> null, parent -> new M749Panel.UsdSelection(action, Path.of("inspection with spaces")));
                ref.set(panel);
                find(panel, JComboBox.class, "transferTransport").setSelectedIndex(0);
                panel.addNotify();
            });
            M749Panel panel = ref.get();
            try {
                await(() -> {
                    JButton button = find(panel, JButton.class, "usdMaintenance");
                    if (!button.isEnabled()) return false;
                    button.doClick(); return true;
                });
                assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
                assertEquals(action.flag, backend.args.get(0));
                assertEquals("inspection with spaces", backend.args.get(1));
                assertEquals(action == M749Panel.UsdAction.APPLY, backend.args.contains("--confirm-usd-erase"));
                assertEquals("PCAN_USBBUS2", backend.args.get(backend.args.indexOf("--channel") + 1));
                SwingUtilities.invokeAndWait(() -> {
                    for (String name : new String[]{"usdMaintenance", "readFlash", "writeFlash", "retry"})
                        assertFalse(find(panel, JButton.class, name).isEnabled());
                });
                backend.release.countDown();
                await(() -> find(panel, JButton.class, "usdMaintenance").isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    String text = find(panel, JLabel.class, "activity").getText();
                    assertTrue(text.contains(action == M749Panel.UsdAction.APPLY ? "unconfirmed" : "complete"), text);
                });
            } finally { backend.release.countDown(); SwingUtilities.invokeAndWait(panel::removeNotify); }
        }
    }

    @Test void cancellingMaintenanceChooserSendsNoCommand() throws Exception {
        Backend backend = new Backend(); AtomicReference<M749Panel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            M749Panel panel = new M749Panel(backend, () -> { throw new IOException("No firmware"); },
                    (parent, read) -> null, parent -> null);
            ref.set(panel); find(panel, JComboBox.class, "transferTransport").setSelectedIndex(0); panel.addNotify();
        });
        try {
            await(() -> {
                JButton b = find(ref.get(), JButton.class, "usdMaintenance");
                if (!b.isEnabled()) return false; b.doClick(); return true;
            });
            assertNull(backend.args);
        } finally { SwingUtilities.invokeAndWait(ref.get()::removeNotify); }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicBoolean done = new AtomicBoolean();
        while (System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(10);
        }
        fail("UI condition timed out");
    }
    private static <T extends Component> T find(Container parent, Class<T> type, String name) {
        for (Component c : parent.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T value = find((Container) c, type, name); if (value != null) return value; }
        }
        return null;
    }
}
