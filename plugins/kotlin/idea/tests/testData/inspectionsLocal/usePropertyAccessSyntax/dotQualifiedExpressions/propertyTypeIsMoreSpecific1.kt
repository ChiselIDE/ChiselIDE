// PROBLEM: none

abstract class KotlinClass : JavaInterface {
    override fun getSomething(): String = ""
}

fun foo(k: KotlinClass) {
    k.<caret>setSomething(1)
}

