#include "pch.h"
#include "storage.h"

// Some learning controllers call these even with every storage backend off.
// Never redirect them to the generic AT32 bank-2 layout: that is OEM loader/NVM.
#if !EFI_CONFIGURATION_STORAGE
void setNeedToWriteConfiguration() {
    efiPrintf("M74.9 settings are volatile: persistent storage is disabled");
}

bool settingsLtftRequestWriteToFlash() {
    return false;
}

bool storageReqestReadID(StorageItemId) {
    return false;
}
#endif
