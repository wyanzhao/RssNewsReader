package com.dailynews.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeCaptureOption

/** Wait for the intended database-backed state, rather than accepting an occasional loading screenshot. */
@OptIn(ExperimentalRoborazziApi::class)
internal class AwaitComposeText(private val text: String) : RoborazziComposeCaptureOption {
    override fun beforeCapture() {
        onView(isRoot()).perform(object : ViewAction {
            override fun getConstraints() = isRoot()
            override fun getDescription() = "wait for Compose text: $text"
            override fun perform(controller: UiController, view: View) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!containsText(view)) {
                    check(System.nanoTime() < deadline) { "Compose did not reach the expected state: $text; observed: ${texts(view)}" }
                    controller.loopMainThreadForAtLeast(10)
                }
                controller.loopMainThreadUntilIdle()
            }
        })
    }

    override fun afterCapture() = Unit

    private fun containsText(view: View): Boolean {
        if (view is ViewRootForTest) {
            view.measureAndLayoutForTest()
            if (containsText(view.semanticsOwner.rootSemanticsNode)) return true
        }
        return view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it)) }
    }

    private fun texts(view: View): List<String> {
        if (view is ViewRootForTest) return texts(view.semanticsOwner.rootSemanticsNode)
        return if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)

    private fun containsText(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || node.children.any(::containsText)
}
