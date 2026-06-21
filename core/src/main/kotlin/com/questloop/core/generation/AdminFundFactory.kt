package com.questloop.core.generation

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin

/**
 * Snapshot of the user's external reward-fund setup, assembled from settings and
 * the completion ledger (no separate source of truth): whether an affordable
 * budget is set, whether the one-off "open a pot" admin quest has been done, and
 * how much of this month's budget has been earned so far.
 */
data class RewardFundState(
    /** The user's self-set affordable monthly budget cap, in their currency. */
    val budgetCap: Double,
    /** Whether the one-off "open a pot" admin quest has been completed. */
    val potOpened: Boolean,
    /** This month's suggested allowance (RewardAllowanceCalculator); 0 if none earned. */
    val suggestedAllowance: Double,
)

/**
 * Turns the reward-fund lifecycle into *admin quests* (SPEC §6) so the steps for
 * running a self-funded reward pot surface in the plan at the right time, instead
 * of the single static seed quest the app shipped with. Pure and deterministic,
 * mirroring [HabitQuestFactory]: ids are stable and namespaced, so recurrence
 * (monthly funding/claiming) and "already done this month" fall out of the normal
 * [QuestScheduler] + completion ledger — there's no extra bookkeeping.
 *
 * Every quest is META_MAINTENANCE, so the reward engine's meta-XP cap keeps these
 * from ever inflating progression. The app never moves money: completing an admin
 * quest only records that the user did the external step themselves.
 */
object AdminFundFactory {

    const val PREFIX = "admin-"
    const val OPEN_POT_ID = "${PREFIX}open-pot"
    const val FUND_MONTH_ID = "${PREFIX}fund-month"
    const val CLAIM_ID = "${PREFIX}claim-allowance"

    fun openPotQuest(): Quest = adminQuest(
        id = OPEN_POT_ID,
        title = "Open a separate savings pot you control",
        frequency = QuestFrequency.ONE_OFF,
        rationale = "A pot only you control, kept apart from everyday money — QuestLoop never touches it.",
    )

    fun fundMonthQuest(): Quest = adminQuest(
        id = FUND_MONTH_ID,
        title = "Move this month's reward budget into your pot",
        frequency = QuestFrequency.MONTHLY,
        rationale = "Set aside this month's amount — only ever what you can comfortably afford.",
    )

    fun claimQuest(): Quest = adminQuest(
        id = CLAIM_ID,
        title = "Release the rewards you've earned this month",
        frequency = QuestFrequency.MONTHLY,
        rationale = "You've earned some of your budget this month — enjoy it from your pot, guilt-free.",
    )

    /**
     * The admin quests that apply right now, in lifecycle order:
     * - no budget set yet → none (the Rewards screen prompts for a budget instead);
     * - budget set, pot not opened → open the pot;
     * - pot opened → fund this month, plus a claim once there's something earned.
     */
    fun deriveAll(state: RewardFundState): List<Quest> {
        if (state.budgetCap <= 0.0) return emptyList()
        if (!state.potOpened) return listOf(openPotQuest())
        val quests = mutableListOf(fundMonthQuest())
        if (state.suggestedAllowance > 0.0) quests += claimQuest()
        return quests
    }

    private fun adminQuest(
        id: String,
        title: String,
        frequency: QuestFrequency,
        rationale: String,
    ): Quest = Quest(
        id = id,
        title = title,
        category = QuestCategory.META_MAINTENANCE,
        frequency = frequency,
        difficulty = Difficulty.EASY,
        origin = QuestOrigin.SYSTEM_RECURRING,
        completionStyle = CompletionStyle.BINARY,
        estimatedMinutes = 5,
        rationale = rationale,
    )
}
