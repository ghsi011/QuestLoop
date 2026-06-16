package com.questloop.app.di

import android.content.Context
import com.questloop.app.data.FileAiDiagnostics
import com.questloop.app.data.ProfileStore
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.WakeLockAiCallGuard
import com.questloop.app.data.local.QuestLoopDatabase

/**
 * Minimal manual dependency container. Keeps the app free of an annotation-
 * processing DI framework (simpler build, fewer moving parts) while still
 * giving ViewModels a single, testable wiring point.
 */
class AppContainer(context: Context) {
    private val db = QuestLoopDatabase.get(context)
    private val profileStore = ProfileStore(context.applicationContext)

    val repository: QuestRepository = QuestRepository(
        questDao = db.questDao(),
        completionDao = db.completionDao(),
        profileStore = profileStore,
        aiDiagnostics = FileAiDiagnostics(context.applicationContext),
        aiCallGuard = WakeLockAiCallGuard(context.applicationContext),
    )
}
