// IGNORE_BACKEND: JVM_IR, JVM_IR_SERIALIZE
// FILE: 1.kt
// SKIP_INLINE_CHECK_IN: inlineFun$default
package test

val ok = "OK"

inline fun inlineFun(lambda: () -> String = ::ok): String {
    return lambda()
}

// FILE: 2.kt

import test.*

fun box(): String {
    return inlineFun()
}
