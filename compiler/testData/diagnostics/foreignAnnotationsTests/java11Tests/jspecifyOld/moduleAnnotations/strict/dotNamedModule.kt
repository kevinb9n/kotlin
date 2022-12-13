// FIR_IDENTICAL
// ALLOW_KOTLIN_PACKAGE
// JSPECIFY_STATE: strict

// FILE: my.sand.box/module-info.java
import org.jspecify.nullness.NullMarked;

@NullMarked
open module my.sand.box {
    requires java9_annotations;
    exports my.test;
}

// FILE: my.sand.box/my/test/Test.java
package my.test;

public class Test {
    public void foo(Integer x) {}
}

// FILE: main.kt
import my.test.Test

fun main(x: Test) {
    x.foo(<!NULL_FOR_NONNULL_TYPE!>null<!>)
}