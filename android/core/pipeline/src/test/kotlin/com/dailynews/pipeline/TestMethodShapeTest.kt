package com.dailynews.pipeline

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * KEEP: prevent the class of "the test exists but never ran".
 *
 * JUnit 5's test-method discovery requires void. Kotlin expression-body
 * functions infer a return type, so a `fun foo() = ...` that ends in
 * `assertFailsWith { }` (returns the exception) or `runBlocking { ... }`
 * (returns the block's value) is quietly not collected — compiles, no
 * warning, test count drops by one.
 *
 * This bug happened twice in this repo: once as R11, once in
 * `EditorialReplayTest` (the only test in this module that pinned the
 * "link normalization" fix; nobody noticed while it was dead). Both were
 * found by eye.
 *
 * Reflects the test classes already loaded in this module and asserts every
 * `@Test` method is void.
 */
class TestMethodShapeTest {
    @Test
    fun `every @Test method in this module returns Unit`() {
        val offenders = testClasses().flatMap { type ->
            type.declaredMethods
                .filter { method -> method.annotations.any { it.annotationClass.qualifiedName == TEST_ANNOTATION } }
                .filter { it.returnType != Void.TYPE }
                .map { "${type.simpleName}.${it.name} 返回 ${it.returnType.simpleName}" }
        }

        assertEquals(
            emptyList(),
            offenders,
            "这些 @Test 方法返回值不是 Unit，JUnit 5 不会收录它们——加显式 `: Unit` 即可",
        )
    }

    /** Also catch "the class was skipped wholesale": this module's test-class count must not collapse to single digits. */
    @Test
    fun `test classes are discoverable from the compiled output`() {
        assertTrue(testClasses().size >= 10, "只发现 ${testClasses().size} 个测试类，编译输出目录可能变了")
    }

    private fun testClasses(): List<Class<*>> {
        val root = File(javaClass.protectionDomain.codeSource.location.toURI())
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "class" && '$' !in it.name }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .mapNotNull { runCatching { Class.forName(it, false, javaClass.classLoader) }.getOrNull() }
            .filter { type ->
                type.declaredMethods.any { method ->
                    method.annotations.any { it.annotationClass.qualifiedName == TEST_ANNOTATION }
                }
            }
            .toList()
    }

    private companion object {
        const val TEST_ANNOTATION = "org.junit.jupiter.api.Test"
    }
}
