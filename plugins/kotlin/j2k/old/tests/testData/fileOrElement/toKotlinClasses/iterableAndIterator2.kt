// ERROR: Return type of 'iterator' is not a subtype of the return type of the overridden member 'public abstract operator fun iterator(): kotlin.collections.Iterator<kotlin.String> defined in kotlin.collections.Iterable'
// ERROR: Return type of 'iterator' is not a subtype of the return type of the overridden member 'public abstract operator fun iterator(): kotlin.collections.Iterator<kotlin.String> defined in kotlin.collections.Iterable'
package demo

import java.util.*

internal class Test : Iterable<String> {
    override fun iterator(): Iterator<String>? {
        return null
    }

    fun push(i: Iterator<String>): Iterator<String> {
        val j = i
        return j
    }
}

internal class FullTest : Iterable<String> {
    override fun iterator(): Iterator<String>? {
        return null
    }

    fun push(i: Iterator<String>): Iterator<String> {
        val j = i
        return j
    }
}