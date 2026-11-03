milestone_is_logged_in = 0xd9d080;
milestone_get_context = 0xd9ca10;
milestone_context_resume = 0xa7fb80;
milestone_login_branch = 0xa7ffdc;
milestone_log_resume = 0xa7fdf8;

SECTIONS {
  .milestone_entry 0xa7fb7c : { *(.text.milestone_entry) }
  .milestone_log_skip 0xa7fda4 : { *(.text.milestone_log_skip) }
  .milestone_guard 0xa7fda8 : { *(.text.milestone_guard) }
  .milestone_wait_log_skip 0xa80b84 : { *(.text.milestone_wait_log_skip) }
  /DISCARD/ : { *(.comment) *(.note*) }
}
