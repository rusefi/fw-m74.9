"""Check built archives, including stale entries left by incremental ZIP updates."""
from io import BytesIO
from pathlib import Path
import re
import zipfile

from test_memory_contract import ROOT, decode_records, image


def bundled_srec(archive, prefix):
    names = [name for name in archive.namelist() if name.endswith(".srec")]
    assert len(names) == 1, f"Expected exactly one SREC, found: {names}"
    pattern = (re.escape(prefix)
               + r"rusefi_[^/]+_[^/]+_re74\.9_(?:[0-9]+|nohash)_[^/]+_update\.srec")
    assert re.fullmatch(pattern, names[0]), f"Unexpected SREC name: {names[0]}"
    return names[0]


def main():
    expected = image.software_image(
        (ROOT / "ext/rusefi/firmware/build/rusefi.elf").read_bytes())
    for suffix in ("", "_autoupdate"):
        path = ROOT / f"ext/rusefi/artifacts/rusefi_bundle_re74.9{suffix}.zip"
        with zipfile.ZipFile(path) as archive:
            prefix = "" if suffix else "rusefi.snapshot.re74.9/"
            srec = bundled_srec(archive, prefix)
            required = {"console/rusefi_console.jar", "console/rusefi_ts_plugin_launcher.jar",
                        "console/release.txt", "console/PCANBasic.dll", "console/PCANBasic_JNI.dll",
                        "console/libpcanbasic_jni.dylib",
                        "rusefi_re74.9.ini", "readme.md", "rusefi.hex", srec.removeprefix(prefix)}
            if not suffix:
                required.update({"rusefi_updater.exe", "rusefi_updater.sh"})
            for name in required:
                assert archive.getinfo(prefix + name).file_size > 0, f"Empty runtime file: {name}"
            with zipfile.ZipFile(BytesIO(archive.read(prefix + "console/rusefi_console.jar"))) as console:
                assert "com/rusefi/m749/M749TabProvider.class" in console.namelist(), "Missing M74.9 UI"
                providers = console.read("META-INF/services/com.rusefi.ui.plugins.ConsoleTabProvider")
                assert b"com.rusefi.m749.M749TabProvider" in providers, "M74.9 UI is not registered"
            formats = set()
            for name in archive.namelist():
                filename = Path(name).name
                if filename.endswith((".bin", ".dfu")) or filename.startswith(
                        ("flash_", "blt_", "openblt_")):
                    raise AssertionError(f"Unsafe/stale bundle entry: {name}")
                if name in (prefix + "rusefi.hex", srec):
                    fmt = filename.rsplit(".", 1)[1]
                    decoded = decode_records(archive.read(name).decode("ascii"), fmt)
                    actual = [(address, bytes(decoded.pop(address + i) for i in range(len(data))))
                              for address, data in expected]
                    assert not decoded, f"Unexpected addresses in {name}"
                    assert actual == expected, f"Payload differs from the ELF in {name}"
                    image.verify_domains(actual)
                    formats.add(fmt)
            assert formats == {"hex", "srec"}, f"Missing software images in {path}"
        print(f"{path.name}: desktop runtime present, whitelisted images, valid CRC, no generic flashing tools or stale binaries")


if __name__ == "__main__":
    main()
