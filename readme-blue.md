# инструкция сделать PCAN из голубого прозрачного свистка

Мы умеем делать хороший PCAN свисток из дешёвого прозрачного голубово свистка

* сначала качаем и ставим https://raw.githubusercontent.com/rusefi/rusefi_external_utils/master/CAN/PEAK-System_Driver-Setup-4.2.3.zip
* качаем https://raw.githubusercontent.com/rusefi/rusefi_external_utils/master/CAN/pcan_canable_hw.hex
* DFU утилита: пользовать проще всего STM32CubeProgrammer - качаем её, она правда 200 мегабайт
* снять прозрачный чехол
* переводим свисток в режим DFU: закоротить две дырочки пинцетом, ножницами или проводочком - и в такой позе вставить в USB
* Windows Device Manager должен сказать STM32 BOOTLOADER
* прошиваем pcan_canable_hw.hex через DFU: STM32CubeProgrammer прошиваем: Select USB → Connect → Erasing & Programming → select firmware → Start Programming.
* передёргиваем питание
* Windows Device Manager говорит PCAN



