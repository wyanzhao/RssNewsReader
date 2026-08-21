package com.dailynews.pipeline

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * KEEP: 防止「测试存在但从未执行」这一类。
 *
 * JUnit 5 的可测方法判定要求返回 void。Kotlin 的表达式体函数会推断返回类型，所以
 * 一个以 `assertFailsWith { }`（返回异常对象）或 `runBlocking { ... }`（返回块的值）
 * 结尾的 `fun foo() = ...` 会安静地不被收录——编译通过、无警告、测试数悄悄少一个。
 *
 * 这个错误在本仓库出现过两次：R11 一次，`EditorialReplayTest` 一次（后者是本模块
 * 里唯一按住"链接归一化"修复的测试，失效期间无人察觉）。两次都是人眼发现的。
 *
 * 这里直接反射本模块已加载的测试类，断言每个 `@Test` 方法都是 void。
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

    /** 同时兜住「类被整体跳过」：本模块的测试类数量不该突然塌到个位数。 */
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
