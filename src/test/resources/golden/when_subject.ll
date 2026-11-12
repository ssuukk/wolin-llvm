target triple = "mos-c64"

define i8 @"t.pick"(i8 %x) {
entry:
  %v0 = alloca i8
  store i8 0, ptr %v0
  %v1 = icmp eq i8 %x, 1
  br i1 %v1, label %when.b0, label %when.n0
when.n0:
  %v2 = icmp eq i8 %x, 2
  %v3 = icmp eq i8 %x, 3
  %v4 = or i1 %v2, %v3
  br i1 %v4, label %when.b1, label %when.e
when.b0:
  store i8 10, ptr %v0
  br label %when.end
when.b1:
  store i8 20, ptr %v0
  br label %when.end
when.e:
  store i8 30, ptr %v0
  br label %when.end
when.end:
  %v5 = load i8, ptr %v0
  ret i8 %v5
}
