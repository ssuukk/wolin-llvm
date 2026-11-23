package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Static methods on real 6502 storage: global, local, array, fixed address. */
class TraitTest {

    @Test fun `a global struct method reads its field`() {
        val bytes = runProgram(
            """
            struct P { var x: ubyte }
            impl P { fun get(): ubyte { return this.x } }
            var p: P
            fun main() {
                p.x = 42
                var out: ubyte^0x400 = 0
                out = p.get()
                while (true) {}
            }
            """
        )
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a method mutates a local struct`() {
        val bytes = runProgram(
            """
            struct P { var x: ubyte }
            impl P {
                fun bump() { this.x++ }
                fun get(): ubyte { return this.x }
            }
            fun main() {
                var p: P
                p.x = 7
                p.bump()
                var out: ubyte^0x400 = 0
                out = p.get()
                while (true) {}
            }
            """
        )
        assertEquals(8, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a method works on an array element`() {
        val bytes = runProgram(
            """
            struct P { var x: ubyte }
            impl P {
                fun bump() { this.x++ }
                fun get(): ubyte { return this.x }
            }
            var ps: P[ubyte, 4]
            fun main() {
                ps[2].x = 5
                ps[2].bump()
                var out: ubyte^0x400 = 0
                out = ps[2].get()
                while (true) {}
            }
            """
        )
        assertEquals(6, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a method works on a fixed-address struct`() {
        val bytes = runProgram(
            """
            struct P { var x: ubyte }
            impl P {
                fun bump() { this.x++ }
                fun get(): ubyte { return this.x }
            }
            var p: P^0x3000
            fun main() {
                p.x = 9
                p.bump()
                var out: ubyte^0x400 = 0
                out = p.get()
                while (true) {}
            }
            """
        )
        assertEquals(10, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a default trait method runs`() {
        val bytes = runProgram(
            """
            trait Shape { fun area(): ubyte { return this.w * this.h } }
            struct R { var w: ubyte; var h: ubyte }
            impl R : Shape { }
            var r: R
            fun main() {
                r.w = 3
                r.h = 4
                var out: ubyte^0x400 = 0
                out = r.area()
                while (true) {}
            }
            """
        )
        assertEquals(12, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a method takes an argument and returns a value`() {
        val bytes = runProgram(
            """
            struct P { var x: ubyte }
            impl P { fun add(n: ubyte): ubyte { this.x += n; return this.x } }
            var p: P
            fun main() {
                p.x = 1
                var out: ubyte^0x400 = 0
                out = p.add(4)
                while (true) {}
            }
            """
        )
        assertEquals(5, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a method calls another method on this`() {
        val bytes = runProgram(
            """
            struct P { var x: ubyte }
            impl P {
                fun a(): ubyte { return 3 }
                fun b(): ubyte { return this.a() }
            }
            var p: P
            fun main() {
                var out: ubyte^0x400 = 0
                out = p.b()
                while (true) {}
            }
            """
        )
        assertEquals(3, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a dyn trait dispatches at runtime`() {
        val bytes = runProgram(
            """
            trait Shape {
                fun area(): uword
                fun tag(): ubyte
                fun name(): ubyte { return 7 }
            }
            struct Rect { var w: ubyte; var h: ubyte }
            struct Circle { var r: ubyte }
            impl Rect : Shape {
                fun area(): uword { return this.w.toUword() * this.h.toUword() }
                fun tag(): ubyte { return 1 }
            }
            impl Circle : Shape {
                fun area(): uword { return this.r.toUword() * this.r.toUword() }
                fun tag(): ubyte { return 2 }
            }
            var rect: Rect
            var circle: Circle
            var shapes: Shape[ubyte, 2]
            var first: Shape = rect
            fun main() {
                rect.w = 2
                rect.h = 3
                circle.r = 4
                shapes[0] = rect
                shapes[1] = circle
                var s: Shape = rect
                var sum: uword = s.area()
                sum += shapes[1].area()
                sum += shapes[0].tag().toUword()
                sum += first.area()
                sum += shapes[1].name().toUword()
                var out: ubyte^0x400 = 0
                out = sum.toUbyte()
                while (true) {}
            }
            """
        )
        // 6 + 16 + 1 + 6 + 7 = 36
        assertEquals(36, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a dyn trait can be rebound to another struct`() {
        val bytes = runProgram(
            """
            trait Sized { fun size(): ubyte }
            struct A { var n: ubyte }
            struct B { var n: ubyte }
            impl A : Sized { fun size(): ubyte { return this.n + 10 } }
            impl B : Sized { fun size(): ubyte { return this.n + 20 } }
            var a: A
            var b: B
            fun main() {
                a.n = 1
                b.n = 2
                var s: Sized = a
                s = b
                var t: Sized = s
                var out: ubyte^0x400 = 0
                out = t.size()
                while (true) {}
            }
            """
        )
        assertEquals(22, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a dyn trait works on a fixed-address struct and as a parameter`() {
        val bytes = runProgram(
            """
            trait P { fun bump(): ubyte }
            struct H { var x: ubyte }
            impl H : P { fun bump(): ubyte { this.x++; return this.x } }
            var h: H^0x3000
            fun call(s: P): ubyte { return s.bump() }
            fun main() {
                h.x = 9
                var s: P = h
                var out: ubyte^0x400 = 0
                out = call(s)
                while (true) {}
            }
            """
        )
        assertEquals(10, bytes[0].toInt() and 0xFF)
    }
}
