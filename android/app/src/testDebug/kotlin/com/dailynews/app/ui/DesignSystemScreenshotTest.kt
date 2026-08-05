package com.dailynews.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.dailynews.app.ui.common.EmptyState
import com.dailynews.app.ui.common.StatusBadge
import com.dailynews.app.ui.theme.DailyNewsTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class DesignSystemScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactLightComponents() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            DailyNewsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("今日", style = androidx.compose.material3.MaterialTheme.typography.displaySmall)
                        StatusBadge("RUNNING", "正在生成摘要")
                        EmptyState("还没有报告", "生成后，Top 30 会直接出现在今日页。", "立即生成") {}
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/design-system-compact-light.png")
    }

}
