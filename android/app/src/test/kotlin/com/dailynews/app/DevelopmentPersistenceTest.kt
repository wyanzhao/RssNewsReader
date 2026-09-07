package com.dailynews.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.config.PipelineConfigRepository
import com.dailynews.data.repo.ReportRepository
import com.dailynews.data.repo.StateBackupRepository
import com.dailynews.data.repo.DeviceStateBackup
import com.dailynews.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DevelopmentPersistenceTest {
    @Test fun publishedEvidenceSurvivesWithoutArticlePoolOrDebugArtifactsAndBackupRoundtrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DailyNewsDatabase::class.java).allowMainThreadQueries().build()
        try {
            val progress = EventDevelopment("2026-01-02", "编译器发布正式版本。", "https://source.example/a", "The compiler is generally available.")
            val report = AssembledReport("2026-09-07", "Report", "Digest", listOf(ReportItem(1, 1, progress.evidenceLink, "Compiler release", "Source", "", "", "编译器发布。", eventKey = "compiler", development = progress)))
            ReportRepository(database, context).publish(report)
            assertEquals(progress, database.reports().topItemsNow("2026-09-07", 30).single().development)
            val backups = StateBackupRepository(database, PipelineConfigRepository(context))
            val out = ByteArrayOutputStream()
            backups.exportZip(out)
            val payload = out.toByteArray()
            val envelope = ZipInputStream(payload.inputStream()).use { zip ->
                var found: String? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "dailynews-state.json") found = zip.readBytes().toString(Charsets.UTF_8)
                }
                ArtifactJson.codec.decodeFromString<DeviceStateBackup>(requireNotNull(found))
            }
            assertEquals(com.dailynews.data.db.DAILYNEWS_SCHEMA_VERSION, envelope.databaseVersion)
            assertEquals(progress, envelope.reportItems.single().development)
            database.reports().deleteItems("2026-09-07")
            backups.importZip(payload)
            assertEquals(progress, database.reports().topItemsNow("2026-09-07", 30).single().development)
        } finally { database.close() }
    }
}
