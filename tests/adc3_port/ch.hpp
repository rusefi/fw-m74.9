#pragma once
extern unsigned adc3TestLockDepth;
namespace chibios_rt {
struct CriticalSectionLocker {
	CriticalSectionLocker() { ++adc3TestLockDepth; }
	~CriticalSectionLocker() { --adc3TestLockDepth; }
};
}
