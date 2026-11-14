target triple = "mos-c64"

define i8 @"t.add"(i8 %a, i8 %b) {
entry:
  %v0 = add i8 %a, %b
  ret i8 %v0
}

define void @__wolin_init_file_t() {
entry:
  ret void
}
