/* Addresses are guarded by the entire bundled libGFrame.so SHA-256 in build.py. */
gframe_animator_description = 0x5e996c;
gframe_ensure_appearance = 0x38c680;
gframe_set_named_animation = 0x667544;
gframe_description_vector_cleanup = 0x38edf4;
gframe_enter_resume = 0x5f5074;
gframe_exit_resume = 0x5f5310;
gframe_inflate_enter_resume = 0x5eecf4;
gframe_inflate_exit_resume = 0x5eeeb4;

SECTIONS {
    .inflate_enter_branch 0x5eecb0 : { *(.text.inflate_enter_branch) }
    .inflate_exit_branch 0x5eee70 : { *(.text.inflate_exit_branch) }
    .fade_enter 0x5f4f40 : { *(.text.fade_enter) }
    .fade_exit 0x5f51c0 : { *(.text.fade_exit) }
    /DISCARD/ : { *(.comment) *(.note*) }
}
