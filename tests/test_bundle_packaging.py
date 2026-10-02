"""Exercise the real ZIP recipes without compiling firmware or Java."""
from pathlib import Path
from io import BytesIO
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

from test_memory_contract import ROOT, elf_fixture, image
from check_bundles import bundled_srec

CONFIG_DEFINITION_JAR = (ROOT / "ext/rusefi/java_tools/configuration_definition"
                         "/build/libs/config_definition-all.jar")


def config_definition_jar():
    """bundle.mk validates the INI with the real generator jar before zipping.

    The firmware Makefile builds it through java_tools.mk, which the fixture
    does not include: its jar rule is forced on every run and depends on the
    documentation sentinel. Build it once here with the same Gradle task.
    """
    if not CONFIG_DEFINITION_JAR.is_file():
        subprocess.run(["./gradlew", "-q", "--console=plain", ":config_definition:shadowJar"],
                       cwd=ROOT / "ext/rusefi", check=True)
    return CONFIG_DEFINITION_JAR


class BundlePackagingTest(unittest.TestCase):
    def test_desktop_runtime_and_clean_archives(self):
        repo = ROOT / "ext/rusefi"
        jar = config_definition_jar()
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
            signature = firmware / "controllers/generated/signature_re74.9.h"
            signature.parent.mkdir(parents=True)
            signature.write_text("#define SIGNATURE_HASH 1234567890\n")
            makefile = firmware / "Makefile"
            makefile.write_text(f"""
PROJECT = rusefi
PROJECT_DIR = {repo / 'firmware'}
BOARD_DIR = {ROOT}
BUILDDIR = build
META_OUTPUT_ROOT_FOLDER =
BUNDLE_SIMULATOR = false
CONSOLE_JAR = ../rusefi_console.jar
TS_PLUGIN_LAUNCHER_JAR = ../rusefi_ts_plugin_launcher.jar
INI_FILE = ../rusefi_re74.9.ini
CONFIG_DEFINITION_JAR = {jar}
include {ROOT / 'board.mk'}
include {repo / 'firmware/bundle.mk'}
""")
            artifacts = work / "artifacts"
            artifacts.mkdir()
            for suffix in ("", "_autoupdate"):
                archive = artifacts / f"rusefi_bundle_re74.9{suffix}.zip"
                with zipfile.ZipFile(archive, "w") as output:
                    output.writestr("obsolete/flash_stlink.sh", "stale script")
                    prefix = "" if suffix else "rusefi.snapshot.re74.9/"
                    output.writestr(prefix + "rusefi_update.srec", "stale image")
            # Rebuild with new metadata while retaining the staging directory.
            # Both ZIP recipes must exclude old names as well as legacy names.
            for date, commit in (("2026-10-02", "abc123"), ("2026-10-03", "def456")):
                self.build_and_check(firmware, artifacts, repo, date, commit)

    def build_and_check(self, firmware, artifacts, repo, date, commit):
        result = subprocess.run(
            ["make", "-r", "-j2", "build_both_bundles",
             f"BUNDLE_DATE={date}", f"GITHUB_SHA={commit}", "AUTOMATION_LTS=false"], cwd=firmware,
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
                expected_srec = f"rusefi_development_{date}_re74.9_1234567890_{commit}_update.srec"
                self.assertEqual(prefix + expected_srec, bundled_srec(archive, prefix))
                self.assertEqual((firmware / "build/rusefi.srec").read_bytes(),
                                 archive.read(prefix + expected_srec))
                self.assertTrue({"rusefi.hex", expected_srec,
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

    def test_archive_checker_rejects_stale_or_wrong_board_srecs(self):
        current = "rusefi_development_2026-10-02_re74.9_1234567890_abc123_update.srec"
        for prefix in ("", "rusefi.snapshot.re74.9/"):
            for names in ([], ["rusefi_update.srec"],
                          [current.replace("re74.9", "hd81")],
                          [current, "rusefi_update.srec"],
                          [current, current.replace("abc123", "def456")]):
                with self.subTest(prefix=prefix, names=names):
                    data = BytesIO()
                    with zipfile.ZipFile(data, "w") as archive:
                        for name in names:
                            archive.writestr(prefix + name, "fixture")
                        with self.assertRaises(AssertionError):
                            bundled_srec(archive, prefix)


if __name__ == "__main__":
    unittest.main()
