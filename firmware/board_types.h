#pragma once

#include <stdint.h>

// Persisted values; keep synchronized with board_config.txt.
enum class LadaCanbusProfile : uint8_t {
    Disabled = 0,
    Largus = 1,
    NivaGranta = 2,
};

static_assert(sizeof(LadaCanbusProfile) == 1);
