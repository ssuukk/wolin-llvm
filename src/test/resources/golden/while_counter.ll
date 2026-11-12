target triple = "mos-c64"

define i16 @"t.loop"() {
entry:
  %v0 = alloca i16
  store i16 0, ptr %v0
  br label %loop
loop:
  %v1 = load i16, ptr %v0
  %v2 = icmp ult i16 %v1, 5
  br i1 %v2, label %body, label %end
body:
  %v3 = load i16, ptr %v0
  %v4 = add i16 %v3, 1
  store i16 %v4, ptr %v0
  br label %loop
end:
  %v5 = load i16, ptr %v0
  ret i16 %v5
}
