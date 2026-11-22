package pl.qus.wolin.ir

/**
 * The hand-written IR runtime for string interpolation.
 *
 * Helpers write into the template's static buffer, never allocate, and stop
 * every store at the 255-byte cap. Only the helpers a file actually uses,
 * plus their dependencies, are emitted into the driver module. The float
 * helper is added separately when a file formats a float.
 */
object InterpRuntime {

    private const val APPEND_CHAR = "__wolin_interp_append_char"
    private const val APPEND_STRING = "__wolin_interp_append_string"
    private const val APPEND_UWORD = "__wolin_interp_append_uword"
    private const val APPEND_UBYTE = "__wolin_interp_append_ubyte"
    private const val APPEND_BYTE = "__wolin_interp_append_byte"
    private const val APPEND_WORD = "__wolin_interp_append_word"
    private const val APPEND_BOOL = "__wolin_interp_append_bool"

    fun emit(used: Set<String>, includeFloat: Boolean): String {
        val needed = linkedSetOf<String>()

        fun need(name: String) {
            if (name in needed) return
            needed += name
            when (name) {
                APPEND_UWORD -> need(APPEND_CHAR)
                APPEND_UBYTE -> need(APPEND_UWORD)
                APPEND_BYTE -> {
                    need(APPEND_UWORD)
                    need(APPEND_CHAR)
                }
                APPEND_WORD -> {
                    need(APPEND_UWORD)
                    need(APPEND_CHAR)
                }
                APPEND_BOOL -> need(APPEND_STRING)
            }
        }

        used.forEach(::need)
        if (includeFloat) {
            need(APPEND_STRING)
            need(APPEND_CHAR)
            need(APPEND_UWORD)
        }

        if (needed.isEmpty()) return ""

        val out = StringBuilder()
        if (APPEND_BOOL in needed) {
            out.append(TRUE_FALSE_CONSTANTS).append('\n')
        }
        if (APPEND_CHAR in needed) out.append(APPEND_CHAR_FN).append('\n')
        if (APPEND_STRING in needed) out.append(APPEND_STRING_FN).append('\n')
        if (APPEND_UWORD in needed) out.append(APPEND_UWORD_FN).append('\n')
        if (APPEND_UBYTE in needed) out.append(APPEND_UBYTE_FN).append('\n')
        if (APPEND_BYTE in needed) out.append(APPEND_BYTE_FN).append('\n')
        if (APPEND_WORD in needed) out.append(APPEND_WORD_FN).append('\n')
        if (APPEND_BOOL in needed) out.append(APPEND_BOOL_FN).append('\n')
        if (includeFloat) out.append(appendFloatRuntime())
        return out.toString().trimEnd()
    }

    private val TRUE_FALSE_CONSTANTS = """
        @__wolin_interp_true = private unnamed_addr constant [5 x i8] c"\54\52\55\45\00"
        @__wolin_interp_false = private unnamed_addr constant [6 x i8] c"\46\41\4C\53\45\00"
    """.trimIndent()

    private val APPEND_CHAR_FN = """
        define i16 @$APPEND_CHAR(ptr %buf, i16 %pos, i8 %ch) {
        entry:
          %full = icmp uge i16 %pos, 255
          br i1 %full, label %done, label %store
        store:
          %dst = getelementptr i8, ptr %buf, i16 %pos
          store i8 %ch, ptr %dst
          %next = add i16 %pos, 1
          br label %done
        done:
          %result = phi i16 [ %pos, %entry ], [ %next, %store ]
          ret i16 %result
        }
    """.trimIndent()

    private val APPEND_STRING_FN = """
        define i16 @$APPEND_STRING(ptr %buf, i16 %pos, ptr %src) {
        entry:
          br label %loop
        loop:
          %p = phi i16 [ %pos, %entry ], [ %pnext, %store ]
          %s = phi ptr [ %src, %entry ], [ %snext, %store ]
          %full = icmp uge i16 %p, 255
          br i1 %full, label %done, label %check
        check:
          %ch = load i8, ptr %s
          %nul = icmp eq i8 %ch, 0
          br i1 %nul, label %done, label %store
        store:
          %dst = getelementptr i8, ptr %buf, i16 %p
          store i8 %ch, ptr %dst
          %pnext = add i16 %p, 1
          %snext = getelementptr i8, ptr %s, i16 1
          br label %loop
        done:
          ret i16 %p
        }
    """.trimIndent()

    /**
     * Unsigned decimal, no leading zeros. It uses `udiv`/`urem` only for
     * constant division by ten; the MOS backend lowers those to compiler-rt
     * integer helpers, not the C library.
     */
    private val APPEND_UWORD_FN = """
        define i16 @$APPEND_UWORD(ptr %buf, i16 %pos, i16 %v) {
        entry:
          %zero = icmp eq i16 %v, 0
          br i1 %zero, label %zero_block, label %find
        zero_block:
          %zpos = call i16 @$APPEND_CHAR(ptr %buf, i16 %pos, i8 48)
          br label %done
        find:
          br label %find_loop
        find_loop:
          %d = phi i16 [ 1, %find ], [ %dnext, %grow ]
          %limit = udiv i16 %v, 10
          %le = icmp ule i16 %d, %limit
          br i1 %le, label %grow, label %digits
        grow:
          %dnext = mul i16 %d, 10
          br label %find_loop
        digits:
          %p = phi i16 [ %pos, %find_loop ], [ %pnext, %next ]
          %rem = phi i16 [ %v, %find_loop ], [ %r, %next ]
          %div = phi i16 [ %d, %find_loop ], [ %divnext, %next ]
          br label %count
        count:
          %r = phi i16 [ %rem, %digits ], [ %rsub, %sub ]
          %c = phi i16 [ 0, %digits ], [ %cnext, %sub ]
          %enough = icmp ult i16 %r, %div
          br i1 %enough, label %emit, label %sub
        sub:
          %rsub = sub i16 %r, %div
          %cnext = add i16 %c, 1
          br label %count
        emit:
          %ch = add i16 %c, 48
          %ch8 = trunc i16 %ch to i8
          %pnext = call i16 @$APPEND_CHAR(ptr %buf, i16 %p, i8 %ch8)
          %last = icmp eq i16 %div, 1
          br i1 %last, label %done, label %next
        next:
          %divnext = udiv i16 %div, 10
          br label %digits
        done:
          %result = phi i16 [ %zpos, %zero_block ], [ %pnext, %emit ]
          ret i16 %result
        }
    """.trimIndent()

    private val APPEND_UBYTE_FN = """
        define i16 @$APPEND_UBYTE(ptr %buf, i16 %pos, i8 %v) {
        entry:
          %wide = zext i8 %v to i16
          %result = call i16 @$APPEND_UWORD(ptr %buf, i16 %pos, i16 %wide)
          ret i16 %result
        }
    """.trimIndent()

    private val APPEND_BYTE_FN = """
        define i16 @$APPEND_BYTE(ptr %buf, i16 %pos, i8 %v) {
        entry:
          %neg = icmp slt i8 %v, 0
          br i1 %neg, label %negative, label %positive
        negative:
          %wide = sext i8 %v to i16
          %mag = sub i16 0, %wide
          %signpos = call i16 @$APPEND_CHAR(ptr %buf, i16 %pos, i8 45)
          %result = call i16 @$APPEND_UWORD(ptr %buf, i16 %signpos, i16 %mag)
          ret i16 %result
        positive:
          %widepos = zext i8 %v to i16
          %resultpos = call i16 @$APPEND_UWORD(ptr %buf, i16 %pos, i16 %widepos)
          ret i16 %resultpos
        }
    """.trimIndent()

    private val APPEND_WORD_FN = """
        define i16 @$APPEND_WORD(ptr %buf, i16 %pos, i16 %v) {
        entry:
          %neg = icmp slt i16 %v, 0
          br i1 %neg, label %negative, label %positive
        negative:
          %mag = sub i16 0, %v
          %signpos = call i16 @$APPEND_CHAR(ptr %buf, i16 %pos, i8 45)
          %result = call i16 @$APPEND_UWORD(ptr %buf, i16 %signpos, i16 %mag)
          ret i16 %result
        positive:
          %resultpos = call i16 @$APPEND_UWORD(ptr %buf, i16 %pos, i16 %v)
          ret i16 %resultpos
        }
    """.trimIndent()

    private val APPEND_BOOL_FN = """
        define i16 @$APPEND_BOOL(ptr %buf, i16 %pos, i1 %v) {
        entry:
          br i1 %v, label %yes, label %no
        yes:
          %yespos = call i16 @$APPEND_STRING(ptr %buf, i16 %pos, ptr @__wolin_interp_true)
          ret i16 %yespos
        no:
          %nopos = call i16 @$APPEND_STRING(ptr %buf, i16 %pos, ptr @__wolin_interp_false)
          ret i16 %nopos
        }
    """.trimIndent()

    /**
     * A self-contained float formatter. It normalises to [1, 10), extracts
     * seven significant digits, rounds on the eighth, and writes fixed
     * notation for decimal exponents -3..6 or `E` notation otherwise.
     *
     * Only compiler-rt soft-float operations are used; no C library call.
     */
    private fun appendFloatRuntime(): String = """
        @__wolin_interp_float_digits = private global [7 x i8] zeroinitializer
        @__wolin_interp_nan = private unnamed_addr constant [4 x i8] c"\CE\41\CE\00"
        @__wolin_interp_inf = private unnamed_addr constant [9 x i8] c"\C9\4E\46\49\4E\49\54\59\00"
        @__wolin_interp_zero = private unnamed_addr constant [4 x i8] c"0.0\00"

        declare float @llvm.fabs.f32(float)

        define i16 @__wolin_interp_append_float(ptr %buf, i16 %pos, float %v) {
        entry:
          %bits = bitcast float %v to i32
          %neg = icmp slt i32 %bits, 0
          %abs = call float @llvm.fabs.f32(float %v)
          %isnan = fcmp uno float %v, %v
          br i1 %isnan, label %nan, label %check_inf
        nan:
          %nanpos = call i16 @$APPEND_STRING(ptr %buf, i16 %pos, ptr @__wolin_interp_nan)
          ret i16 %nanpos
        check_inf:
          %isinf = fcmp oeq float %abs, 0x7FF0000000000000
          br i1 %isinf, label %inf, label %check_zero
        inf:
          br i1 %neg, label %inf_neg, label %inf_pos
        inf_neg:
          %infn1 = call i16 @$APPEND_CHAR(ptr %buf, i16 %pos, i8 45)
          %infn2 = call i16 @$APPEND_STRING(ptr %buf, i16 %infn1, ptr @__wolin_interp_inf)
          ret i16 %infn2
        inf_pos:
          %infp = call i16 @$APPEND_STRING(ptr %buf, i16 %pos, ptr @__wolin_interp_inf)
          ret i16 %infp
        check_zero:
          %iszero = fcmp oeq float %abs, 0.0
          br i1 %iszero, label %zero, label %norm_hi
        zero:
          br i1 %neg, label %zero_neg, label %zero_pos
        zero_neg:
          %zn1 = call i16 @$APPEND_CHAR(ptr %buf, i16 %pos, i8 45)
          %zn2 = call i16 @$APPEND_STRING(ptr %buf, i16 %zn1, ptr @__wolin_interp_zero)
          ret i16 %zn2
        zero_pos:
          %zp = call i16 @$APPEND_STRING(ptr %buf, i16 %pos, ptr @__wolin_interp_zero)
          ret i16 %zp
        norm_hi:
          %m0 = phi float [ %abs, %check_zero ], [ %m1, %div_hi ]
          %e0 = phi i16 [ 0, %check_zero ], [ %e1, %div_hi ]
          %hi = fcmp oge float %m0, 10.0
          br i1 %hi, label %div_hi, label %norm_lo
        div_hi:
          %m1 = fdiv float %m0, 10.0
          %e1 = add i16 %e0, 1
          br label %norm_hi
        norm_lo:
          br label %lo_loop
        lo_loop:
          %m2 = phi float [ %m0, %norm_lo ], [ %m3, %mul_lo ]
          %e2 = phi i16 [ %e0, %norm_lo ], [ %e3, %mul_lo ]
          %low = fcmp olt float %m2, 0.9999999
          br i1 %low, label %mul_lo, label %digits
        mul_lo:
          %m3 = fmul float %m2, 10.0
          %e3 = add i16 %e2, -1
          br label %lo_loop
        digits:
          %lowfix = fcmp olt float %m2, 1.0
          %mstart = select i1 %lowfix, float 1.0, float %m2
          %dptr = getelementptr [7 x i8], ptr @__wolin_interp_float_digits, i32 0, i32 0
          br label %dig_loop
        dig_loop:
          %i = phi i16 [ 0, %digits ], [ %i2, %dig_loop ]
          %m = phi float [ %mstart, %digits ], [ %mnext, %dig_loop ]
          %d = fptoui float %m to i8
          %dp = getelementptr i8, ptr %dptr, i16 %i
          store i8 %d, ptr %dp
          %df = uitofp i8 %d to float
          %frac = fsub float %m, %df
          %mnext = fmul float %frac, 10.0
          %i2 = add i16 %i, 1
          %more = icmp ult i16 %i2, 7
          br i1 %more, label %dig_loop, label %round
        round:
          %next = fptoui float %mnext to i8
          %roundup = icmp uge i8 %next, 5
          br i1 %roundup, label %carry_start, label %find_last
        carry_start:
          br label %carry_loop
        carry_loop:
          %ci = phi i16 [ 6, %carry_start ], [ %ci2, %carry_zero ]
          %cp = getelementptr i8, ptr %dptr, i16 %ci
          %cd = load i8, ptr %cp
          %inc = add i8 %cd, 1
          %over = icmp ugt i8 %inc, 9
          br i1 %over, label %carry_zero, label %carry_done
        carry_zero:
          store i8 0, ptr %cp
          %ci2 = sub i16 %ci, 1
          %below = icmp slt i16 %ci2, 0
          br i1 %below, label %carry_all, label %carry_loop
        carry_done:
          store i8 %inc, ptr %cp
          br label %find_last
        carry_all:
          store i8 1, ptr %dptr
          %e4 = add i16 %e2, 1
          br label %find_last
        find_last:
          %e10 = phi i16 [ %e2, %round ], [ %e2, %carry_done ], [ %e4, %carry_all ]
          br label %last_loop
        last_loop:
          %li = phi i16 [ 6, %find_last ], [ %li2, %last_next ]
          %lp = getelementptr i8, ptr %dptr, i16 %li
          %ld = load i8, ptr %lp
          %nz = icmp ne i8 %ld, 0
          br i1 %nz, label %emit_dispatch, label %last_next
        last_next:
          %li2 = sub i16 %li, 1
          %has = icmp sge i16 %li2, 0
          br i1 %has, label %last_loop, label %all_zero
        all_zero:
          br label %emit_dispatch
        emit_dispatch:
          %last = phi i16 [ %li, %last_loop ], [ 0, %all_zero ]
          %choose_sci_low = icmp slt i16 %e10, -3
          %choose_sci_high = icmp sgt i16 %e10, 6
          %choose_sci = or i1 %choose_sci_low, %choose_sci_high
          br i1 %neg, label %sign, label %no_sign
        sign:
          %signpos = call i16 @$APPEND_CHAR(ptr %buf, i16 %pos, i8 45)
          br label %notation
        no_sign:
          br label %notation
        notation:
          %startpos = phi i16 [ %signpos, %sign ], [ %pos, %no_sign ]
          br i1 %choose_sci, label %scientific, label %fixed
        fixed:
          %p = add i16 %e10, 1
          %p_nonpos = icmp sle i16 %p, 0
          br i1 %p_nonpos, label %fixed_lead, label %fixed_int
        fixed_lead:
          %fl1 = call i16 @$APPEND_CHAR(ptr %buf, i16 %startpos, i8 48)
          %fl2 = call i16 @$APPEND_CHAR(ptr %buf, i16 %fl1, i8 46)
          %lz = sub i16 0, %p
          br label %lead_loop
        lead_loop:
          %lzi = phi i16 [ 0, %fixed_lead ], [ %lzi2, %lead_body ]
          %lzpos = phi i16 [ %fl2, %fixed_lead ], [ %lzpos2, %lead_body ]
          %lzmore = icmp ult i16 %lzi, %lz
          br i1 %lzmore, label %lead_body, label %lead_digits
        lead_body:
          %lzpos2 = call i16 @$APPEND_CHAR(ptr %buf, i16 %lzpos, i8 48)
          %lzi2 = add i16 %lzi, 1
          br label %lead_loop
        lead_digits:
          br label %lead_digit_loop
        lead_digit_loop:
          %ldi = phi i16 [ 0, %lead_digits ], [ %ldi2, %lead_digit_body ]
          %ldpos = phi i16 [ %lzpos, %lead_digits ], [ %ldpos2, %lead_digit_body ]
          %ldmore = icmp ule i16 %ldi, %last
          br i1 %ldmore, label %lead_digit_body, label %fixed_done
        lead_digit_body:
          %ldp = getelementptr i8, ptr %dptr, i16 %ldi
          %ldc = load i8, ptr %ldp
          %ldch = add i8 %ldc, 48
          %ldpos2 = call i16 @$APPEND_CHAR(ptr %buf, i16 %ldpos, i8 %ldch)
          %ldi2 = add i16 %ldi, 1
          br label %lead_digit_loop
        fixed_int:
          %last1 = add i16 %last, 2
          %p_gt_last = icmp ugt i16 %p, %last
          %limit = select i1 %p_gt_last, i16 %p, i16 %last1
          br label %fixed_int_loop
        fixed_int_loop:
          %j = phi i16 [ 0, %fixed_int ], [ %j2, %int_next ]
          %ipos = phi i16 [ %startpos, %fixed_int ], [ %iposnext, %int_next ]
          %jdone = icmp uge i16 %j, %limit
          br i1 %jdone, label %fixed_int_after, label %int_choose
        int_choose:
          %is_dot = icmp eq i16 %j, %p
          br i1 %is_dot, label %int_dot, label %int_digit
        int_dot:
          %iposdot = call i16 @$APPEND_CHAR(ptr %buf, i16 %ipos, i8 46)
          br label %int_next
        int_digit:
          %after_dot = icmp sgt i16 %j, %p
          %jm1 = sub i16 %j, 1
          %di = select i1 %after_dot, i16 %jm1, i16 %j
          %has_digit = icmp ule i16 %di, %last
          br i1 %has_digit, label %int_load, label %int_zero
        int_load:
          %dp2 = getelementptr i8, ptr %dptr, i16 %di
          %dc = load i8, ptr %dp2
          %dch = add i8 %dc, 48
          %iposload = call i16 @$APPEND_CHAR(ptr %buf, i16 %ipos, i8 %dch)
          br label %int_next
        int_zero:
          %iposzero = call i16 @$APPEND_CHAR(ptr %buf, i16 %ipos, i8 48)
          br label %int_next
        int_next:
          %iposnext = phi i16 [ %iposdot, %int_dot ], [ %iposload, %int_load ], [ %iposzero, %int_zero ]
          %j2 = add i16 %j, 1
          br label %fixed_int_loop
        fixed_int_after:
          %needs_zero = icmp ugt i16 %p, %last
          br i1 %needs_zero, label %fixed_add_zero, label %fixed_done
        fixed_add_zero:
          %zdot = call i16 @$APPEND_CHAR(ptr %buf, i16 %ipos, i8 46)
          %zzero = call i16 @$APPEND_CHAR(ptr %buf, i16 %zdot, i8 48)
          br label %fixed_done
        fixed_done:
          %fixed_result = phi i16 [ %ipos, %fixed_int_after ], [ %zzero, %fixed_add_zero ], [ %ldpos, %lead_digit_loop ]
          ret i16 %fixed_result
        scientific:
          %sd = load i8, ptr %dptr
          %sdch = add i8 %sd, 48
          %sp1 = call i16 @$APPEND_CHAR(ptr %buf, i16 %startpos, i8 %sdch)
          %sp2 = call i16 @$APPEND_CHAR(ptr %buf, i16 %sp1, i8 46)
          %last_zero = icmp eq i16 %last, 0
          br i1 %last_zero, label %sci_zero, label %sci_loop
        sci_zero:
          %sz = call i16 @$APPEND_CHAR(ptr %buf, i16 %sp2, i8 48)
          br label %sci_exp
        sci_loop:
          %si = phi i16 [ 1, %scientific ], [ %si2, %sci_body ]
          %spos = phi i16 [ %sp2, %scientific ], [ %spos2, %sci_body ]
          %smore = icmp ule i16 %si, %last
          br i1 %smore, label %sci_body, label %sci_exp
        sci_body:
          %sdp = getelementptr i8, ptr %dptr, i16 %si
          %sdc = load i8, ptr %sdp
          %sdch2 = add i8 %sdc, 48
          %spos2 = call i16 @$APPEND_CHAR(ptr %buf, i16 %spos, i8 %sdch2)
          %si2 = add i16 %si, 1
          br label %sci_loop
        sci_exp:
          %epos = phi i16 [ %sz, %sci_zero ], [ %spos, %sci_loop ]
          %ep = call i16 @$APPEND_CHAR(ptr %buf, i16 %epos, i8 -59)
          %eneg = icmp slt i16 %e10, 0
          br i1 %eneg, label %sci_neg, label %sci_pos
        sci_neg:
          %en1 = call i16 @$APPEND_CHAR(ptr %buf, i16 %ep, i8 45)
          %emag = sub i16 0, %e10
          %en2 = call i16 @$APPEND_UWORD(ptr %buf, i16 %en1, i16 %emag)
          ret i16 %en2
        sci_pos:
          %epos2 = call i16 @$APPEND_UWORD(ptr %buf, i16 %ep, i16 %e10)
          ret i16 %epos2
        }
    """.trimIndent()
}
