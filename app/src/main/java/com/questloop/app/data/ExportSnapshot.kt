package com.questloop.app.data

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.Quest
import com.questloop.core.model.UserProfile
import kotlinx.serialization.Serializable

/**
 * A complete, portable snapshot of the user's on-device data (SPEC §9: data
 * portability). Serialised to JSON for export; all members are core @Serializable
 * models so the format is stable and human-readable.
 */
@Serializable
data class ExportSnapshot(
    val version: Int = CURRENT_VERSION,
    val quests: List<Quest>,
    val completions: List<CompletionRecord>,
    val profile: UserProfile,
    /** Ids of quests that were archived at export time, re-archived on import. */
    val archivedIds: List<String> = emptyList(),
) {
    companion object {
        /** Bump when the snapshot shape changes incompatibly; import rejects newer. */
        const val CURRENT_VERSION = 1
    }
}
