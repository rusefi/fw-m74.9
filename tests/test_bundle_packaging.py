"""Exercise the real ZIP recipes without compiling firmware or Java."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

from test_memory_contract import ROOT, elf_fixture, image


class BundlePackagingTest(unittest.TestCase):
    def test_desktop_runtime_and_clean_archives(self):
        repo = ROOT / "ext/rusefi"
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            firmware = work / "firmware"
            build = firmware / "build"
            build.mkdir(parents=True)
            # Use real launchers/native libraries, with small placeholder JAR/INI
            # inputs: this test exercises packaging, not their implementations.
            (work / "misc").symlink_to(repo / "misc", target_is_directory=True)
            (work / "java_console").symlink_to(repo / "java_console", target_is_directory=True)
            for name in ("rusefi_console.jar", "rusefi_ts_plugin_launcher.jar", "rusefi_re74.9.ini"):
                (work / name).write_text("packaging fixture\n")
            elf = elf_fixture()
            (build / "rusefi.elf").write_bytes(elf)
            (build / "rusefi.hex").write_text(image.encode(image.software_image(elf), "hex"))
            makefile = firmware / "Makefile"
            makefile.write_text(f"""
PROJECT = rusefi
PROJECT_DIR = {repo / 'firmware'}
BOARD_DIR = {ROOT}
BUILDDIR = build
BUNDLE_SIMULATOR = false
CONSOLE_JAR = ../rusefi_console.jar
TS_PLUGIN_LAUNCHER_JAR = ../rusefi_ts_plugin_launcher.jar
INI_FILE = ../rusefi_re74.9.ini
include {ROOT / 'board.mk'}
include {repo / 'firmware/bundle.mk'}
""")
            artifacts = work / "artifacts"
            artifacts.mkdir()
            for suffix in ("", "_autoupdate"):
                archive = artifacts / f"rusefi_bundle_re74.9{suffix}.zip"
                with zipfile.ZipFile(archive, "w") as output:
                    output.writestr("obsolete/flash_stlink.sh", "stale script")
            result = subprocess.run(
                ["make", "-r", "-j2", "build_both_bundles"], cwd=firmware,
                # bundle.mk runs the image script through $(PYTHON), which the
                # top-level firmware Makefile normally provides.
                env={**os.environ, "PYTHON": sys.executable},
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for suffix in ("", "_autoupdate"):
                with self.subTest(suffix=suffix), zipfile.ZipFile(
                        artifacts / f"rusefi_bundle_re74.9{suffix}.zip") as archive:
                    prefix = "" if suffix else "rusefi.snapshot.re74.9/"
                    names = set(archive.namelist())
                    self.assertNotIn("obsolete/flash_stlink.sh", names)
                    self.assertTrue(all(name.startswith(prefix) for name in names))
                    relative = {name.removeprefix(prefix) for name in names}
                    self.assertTrue({"rusefi.hex", "rusefi_update.srec",
                                     "console/rusefi_console.jar"} <= relative)
                    for launcher in ("rusefi_updater.exe", "rusefi_updater.sh"):
                        if suffix:
                            self.assertNotIn(launcher, relative)
                        else:
                            self.assertEqual(archive.read(prefix + launcher),
                                (repo / "misc/console_launcher" / launcher).read_bytes())
                    for dll in ("PCANBasic.dll", "PCANBasic_JNI.dll"):
                        self.assertEqual(archive.read(prefix + "console/" + dll),
                            (repo / "java_console" / dll).read_bytes())
                    self.assertFalse(any(name.startswith(("bin/", "drivers/",
                        "console/STM32_Programmer_CLI/")) for name in relative))
                    self.assertFalse(any(name.endswith((".bin", ".dfu")) for name in relative))


if __name__ == "__main__":
    unittest.main()
