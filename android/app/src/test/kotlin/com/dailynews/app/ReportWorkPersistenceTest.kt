package com.dailynews.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.repo.ReportWorkRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReportWorkPersistenceTest {
    @Test fun sameWorkReopensAtLatestRunWhileNewWorkDoesNotInheritIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("report_work_resume", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val repository = ReportWorkRepository(context)
            assertNull(repository.read("work-a"))
            repository.bind("work-a", "first-run", "2026-09-07")
            assertEquals("first-run", ReportWorkRepository(context).read("work-a")?.runId)
            repository.bind("work-a", "interrupted-recovery", "2026-09-07")
            val reopened = ReportWorkRepository(context)
            assertEquals("interrupted-recovery", reopened.read("work-a")?.runId)
            assertEquals("2026-09-07", reopened.read("work-a")?.reportDate)
            assertNull(reopened.read("work-b"))
            prefs.edit().putString("binding", "{broken").commit()
            assertFails { reopened.read("work-a") }
        } finally { prefs.edit().clear().commit() }
    }
}
