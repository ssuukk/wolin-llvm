target triple = "mos-c64"

define i8 @"t.f"() {
entry:
  %v0 = alloca i8
  %v1 = alloca i8
  %v2 = alloca i8
  store i8 5, ptr %v0
  store i8 0, ptr %v1
  store i8 0, ptr %v2
  %v3 = load i8, ptr %v0
  %v4 = add i8 %v3, 1
  store i8 %v4, ptr %v0
  store i8 %v3, ptr %v1
  %v5 = load i8, ptr %v0
  %v6 = add i8 %v5, 1
  store i8 %v6, ptr %v0
  store i8 %v6, ptr %v2
  %v7 = load i8, ptr %v0
  %v8 = sub i8 %v7, 1
  store i8 %v8, ptr %v0
  %v9 = load i8, ptr %v1
  %v10 = load i8, ptr %v2
  %v11 = add i8 %v9, %v10
  ret i8 %v11
}

define void @__wolin_init_file_t() {
entry:
  ret void
}
