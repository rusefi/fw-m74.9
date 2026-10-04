"""Compile the production ADC3 port against a deterministic HAL fixture."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class Adc3PortTest(unittest.TestCase):
    def test_port(self):
        root = Path(__file__).resolve().parents[1]
        firmware = root / "ext/rusefi/firmware"
        fixture = root / "tests/adc3_port"
        compiler = os.environ.get("CXX", "c++")
        with tempfile.TemporaryDirectory(prefix="m749-adc3-") as temporary:
            binary = Path(temporary) / "adc3_port_test.exe"
            includes = [fixture, root, firmware, firmware / "hw_layer/adc"]
            sources = [fixture / "main.cpp", firmware / "hw_layer/ports/stm32/stm32_adc_v2_adc3.cpp"]
            if Path(compiler).name.lower() in ("cl", "cl.exe"):
                arguments = ["/nologo", "/std:c++20", "/EHsc"]
                arguments += [f"/I{path}" for path in includes]
                arguments += [str(path) for path in sources] + [f"/Fe:{binary}"]
            else:
                arguments = ["-std=c++20", "-Wall", "-Wextra", "-Werror"]
                arguments += [f"-I{path}" for path in includes]
                arguments += [str(path) for path in sources] + ["-o", str(binary)]
            subprocess.run([compiler, *arguments], cwd=temporary, check=True)
            subprocess.run([str(binary)], cwd=temporary, check=True)


if __name__ == "__main__":
    unittest.main()
