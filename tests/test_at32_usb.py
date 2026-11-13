"""Check OTG PHY configuration using the production driver and register definitions."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


FIRMWARE = Path(__file__).resolve().parents[1] / "ext/rusefi/firmware"
OTG = "ChibiOS/os/hal/ports/STM32/LLD/OTGv1"


class UsbPhyConfigurationTest(unittest.TestCase):
    def check_gccfg(self, platform, no_vbus, expected):
        if platform == "at32":
            platform_config = '''
#define AT32F4XX
#define AT32F435xx
#include "ChibiOS/os/hal/ports/AT32/AT32F4xx/at32_registry.h"
'''
        else:
            platform_config = f"#define STM32_OTG_STEPPING {platform}\n"

        source = platform_config + f'''
#define HAL_USE_USB 1
#define STM32_USB_USE_OTG1 1
#define STM32_USB_USE_OTG2 0
{ '#define BOARD_OTG_NOVBUSSENS 1' if no_vbus else '' }
#include "{OTG}/stm32_otg.h"
#include "{OTG}/hal_usb_lld.c"
#if GCCFG_INIT_VALUE != {expected}
#error Incorrect PHY power/VBUS configuration
#endif
'''
        with tempfile.TemporaryDirectory(prefix="m749-usb-") as temporary:
            # Preprocessing exercises the real configuration branches without
            # requiring an ARM compiler or mocking the USB protocol state machine.
            Path(temporary, "hal.h").write_text("")
            result = subprocess.run(
                [os.environ.get("CC", "cc"), "-E", "-P", "-x", "c",
                 "-I", temporary, "-I", str(FIRMWARE), "-"],
                input=source, text=True, capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_at32_vbus_ignore(self):
        # AT32 requires VBUSIG=1 for a D+/D-/GND-only connection.
        # Keep reserved GCCFG bits 18/19 clear in both configurations.
        self.check_gccfg("at32", True, 0x00210000)
        self.check_gccfg("at32", False, 0x00010000)

    def test_stm32_stepping1(self):
        self.check_gccfg(1, True, 0x00210000)
        self.check_gccfg(1, False, 0x000D0000)

    def test_stm32_stepping2(self):
        self.check_gccfg(2, True, 0x00010000)
        self.check_gccfg(2, False, 0x00210000)


if __name__ == "__main__":
    unittest.main()
