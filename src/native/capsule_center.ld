/* Complete Common and GFrame hashes are checked by build.py before patching. */
capsule_hide = 0x1141b60;
capsule_text_gravity = 0x1142980;
capsule_gravity_slot = 0x12c8c88;

SECTIONS {
    .capsule_center 0xc35fc0 : { *(.text.capsule_center) }
    /DISCARD/ : { *(.comment) *(.note*) }
}
