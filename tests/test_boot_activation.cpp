#include "../firmware/boot_activation.h"
#include <array>
#include <cassert>
#include <cstring>
#include <vector>

struct Flash {
    std::array<uint8_t, m749::MarkerPageSize> page;
    std::vector<size_t> writes;
    int erases = 0;
    int failWrite = -1;
    bool failErase = false;
    bool corruptBody = false;

    Flash() {
        page.fill(0xFF);
        word(m749::ProgrammingMarker);
        page[100] = 0xA5;
    }
    void word(uint32_t marker) {
        for (unsigned i = 0; i < 4; i++) {
            page[i] = uint8_t(marker >> (8 * i));
        }
    }
    uint8_t read(uint32_t address) {
        assert(address >= m749::MarkerAddress && address < m749::MarkerAddress + page.size());
        return page[address - m749::MarkerAddress];
    }
    bool eraseMarkerPage() {
        erases++;
        if (failErase) {
            return false;
        }
        page.fill(0xFF);
        return true;
    }
    bool programMarkerPage(size_t offset, const uint8_t* bytes, size_t length) {
        assert(offset + length <= page.size());
        writes.push_back(offset);
        if (int(writes.size()) == failWrite) {
            return false;
        }
        memcpy(page.data() + offset, bytes, length);
        if (corruptBody && offset != 0) {
            page[100] ^= 1;
        }
        return true;
    }
};

int main() {
    uint8_t saved[m749::MarkerPageSize];
    m749::ImageChecks valid{1, 2, m749::I865BootCrc, true};
    auto invalid = valid;
    invalid.valid = false;
    for (auto token : {0U, 0x4DF9123BU}) {
        Flash flash;
        assert(!m749::activate(flash, valid, token, saved));
        assert(flash.erases == 0);
    }
    {
        Flash flash;
        assert(!m749::activate(flash, invalid, 0xF9C74A52, saved));
        assert(flash.erases == 0);
        flash.word(0x12345678);
        assert(!m749::activate(flash, valid, 0xF9C74A52, saved));
        assert(flash.erases == 0);
    }
    for (auto marker : {m749::ProgrammingMarker, 0xFFFFFFFFU}) {
        Flash flash;
        flash.word(marker);
        const auto original = flash.page;
        assert(m749::activate(flash, valid, 0xF9C74A52, saved));
        assert(flash.erases == 1);
        assert((flash.writes == std::vector<size_t>{4, 0}));
        assert(memcmp(flash.page.data() + 4, original.data() + 4, original.size() - 4) == 0);
        // Cold boot has no return token and performs no further flash operations.
        assert(m749::activate(flash, valid, 0, saved));
        assert(flash.erases == 1);
        assert(!m749::activate(flash, invalid, 0, saved));
    }
    for (int failure = 0; failure < 4; failure++) {
        Flash flash;
        flash.failErase = failure == 0;
        flash.failWrite = failure == 1 ? 1 : failure == 2 ? 2 : -1;
        flash.corruptBody = failure == 3;
        assert(!m749::activate(flash, valid, 0xF9C74A52, saved));
        assert(m749::readWord([&](uint32_t a) { return flash.read(a); }, m749::MarkerAddress) != m749::NormalMarker);
        if (failure != 2) {
            for (size_t offset : flash.writes) {
                assert(offset != 0); // Do not publish the marker after a body/erase failure.
            }
        }
    }
    const char* check = "123456789";
    int pulses = 0;
    assert(m749::crcRange([&](uint32_t i) { return uint8_t(check[i]); }, [&] { pulses++; },
                         0, 9, 0xFFFFFFFF) == 0x0376E6E7);
    assert(pulses == 1);
    // Exercise domain boundaries and the pinned loader requirement against a
    // complete synthetic image with self-consistent trailers but an unknown loader.
    std::vector<uint8_t> image(0x22E000, 0xFF);
    auto read = [&](uint32_t address) { return image.at(address - 0x08000000); };
    auto writeWord = [&](uint32_t address, uint32_t value) {
        for (unsigned i = 0; i < 4; i++) {
            image.at(address - 0x08000000 + i) = uint8_t(value >> (8 * i));
        }
    };
    writeWord(0x08001000, 0x20020000);
    writeWord(0x08001004, 0x08080001);
    auto checked = m749::checkImages(read, [] {});
    writeWord(0x080FFFFC, checked.software);
    writeWord(0x0807FFFC, checked.calibration);
    writeWord(0x0822DFFC, checked.boot);
    auto consistent = m749::checkImages(read, [] {});
    assert(!consistent.valid && consistent.boot != m749::I865BootCrc);
    image[0x60000] ^= 1;
    auto changed = m749::checkImages(read, [] {});
    assert(changed.software == consistent.software && changed.boot == consistent.boot);
    assert(changed.calibration != consistent.calibration);
    image[0x80000] ^= 1;
    changed = m749::checkImages(read, [] {});
    assert(changed.software != consistent.software && changed.boot == consistent.boot);

    // Construct synthetic loader CRC domains for both profiles. Solve the final
    // four input bytes as a GF(2) linear system; no original firmware is needed.
    auto setBootCrc = [&](uint32_t target) {
        const uint32_t patch = 0x0822DFF8;
        auto prefix = m749::crcRange(read, [] {}, 0x08000000, 0x1000, 0xFFFFFFFF);
        prefix = m749::crcRange(read, [] {}, 0x08201000, 0x2CFF8, prefix);
        auto tailCrc = [&](uint32_t value) {
            writeWord(patch, value);
            return m749::crcRange(read, [] {}, patch, 4, prefix);
        };
        const uint32_t baseline = tailCrc(0);
        uint32_t basis[32]{}, masks[32]{};
        for (unsigned bit = 0; bit < 32; bit++) {
            uint32_t value = tailCrc(1U << bit) ^ baseline;
            uint32_t mask = 1U << bit;
            for (int pivot = 31; pivot >= 0; pivot--) {
                if (!(value & (1U << pivot))) continue;
                if (basis[pivot]) { value ^= basis[pivot]; mask ^= masks[pivot]; }
                else { basis[pivot] = value; masks[pivot] = mask; break; }
            }
        }
        uint32_t value = target ^ baseline, solution = 0;
        for (int pivot = 31; pivot >= 0; pivot--) {
            if (value & (1U << pivot)) { assert(basis[pivot]); value ^= basis[pivot]; solution ^= masks[pivot]; }
        }
        assert(value == 0 && tailCrc(solution) == target);
        writeWord(0x0822DFFC, target);
    };
    for (auto boot : {m749::I865BootCrc, m749::I812BootCrc}) {
        setBootCrc(boot);
        checked = m749::checkImages(read, [] {});
        writeWord(0x080FFFFC, checked.software);
        writeWord(0x0807FFFC, checked.calibration);
        assert(m749::checkImages(read, [] {}).valid);
        // I812 preserves 60000..68FFF without including it in custom software
        // or the retained I812 calibration CRC. I865 includes it in calibration.
        image[0x60000] ^= 1;
        assert(m749::checkImages(read, [] {}).valid == (boot == m749::I812BootCrc));
        image[0x60000] ^= 1;
        image[0x69000] ^= 1;
        assert(!m749::checkImages(read, [] {}).valid);
        image[0x69000] ^= 1;
        image[0x201000] ^= 1;
        assert(!m749::checkImages(read, [] {}).valid);
        image[0x201000] ^= 1;
        writeWord(0x08001004, 0x08080003);
        assert(!m749::checkImages(read, [] {}).valid);
        writeWord(0x08001004, 0x08080001);
        assert(m749::checkImages(read, [] {}).valid);
    }
}
