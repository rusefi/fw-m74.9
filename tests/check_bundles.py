"""Check built archives, including stale entries left by incremental ZIP updates."""
from pathlib import Path
import zipfile

from test_memory_contract import ROOT, decode_records, image


def main():
    expected = image.software_image(
        (ROOT / "ext/rusefi/firmware/build/rusefi.elf").read_bytes())
    for suffix in ("", "_autoupdate"):
        path = ROOT / f"ext/rusefi/artifacts/rusefi_bundle_re74.9{suffix}.zip"
        with zipfile.ZipFile(path) as archive:
            formats = set()
            for name in archive.namelist():
                filename = Path(name).name
                if filename.endswith((".bin", ".dfu")) or filename.startswith(
                        ("rusefi_updater", "flash_", "blt_", "openblt_")):
                    raise AssertionError(f"Unsafe/stale bundle entry: {name}")
                if filename in ("rusefi.hex", "rusefi_update.srec"):
                    fmt = filename.rsplit(".", 1)[1]
                    decoded = decode_records(archive.read(name).decode("ascii"), fmt)
                    actual = [(address, bytes(decoded.pop(address + i) for i in range(len(data))))
                              for address, data in expected]
                    assert not decoded, f"Unexpected addresses in {name}"
                    assert actual == expected, f"Payload differs from the ELF in {name}"
                    image.verify_domains(actual)
                    formats.add(fmt)
            assert formats == {"hex", "srec"}, f"Missing software images in {path}"
        print(f"{path.name}: whitelisted images, valid CRC, no generic flashing tools or stale binaries")


if __name__ == "__main__":
    main()
