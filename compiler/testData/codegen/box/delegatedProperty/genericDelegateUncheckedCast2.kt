// IGNORE_BACKEND: JVM, JS

import kotlin.reflect.KProperty
import kotlin.test.*

class Delegate<T>(var inner: T) {
    operator fun getValue(t: Any?, p: KProperty<*>): T = inner
    operator fun setValue(t: Any?, p: KProperty<*>, i: T) { inner = i }
}

val del = Delegate("zzz")

class A {
    inner class B {
        var prop: String by del
    }
}

fun box(): String {
    val c = A().B()

    (del as Delegate<String?>).inner = null
    assertFailsWith<TypeCastException> { c.prop } // does not fail in JVM and JS due to KT-8135.

    return "OK"
}
