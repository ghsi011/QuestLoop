package com.questloop.core.generation

import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminFundFactoryTest {

    private fun state(
        budgetCap: Double = 50.0,
        potOpened: Boolean = true,
        suggestedAllowance: Double = 0.0,
    ) = RewardFundState(budgetCap, potOpened, suggestedAllowance)

    @Test
    fun `no budget set yields no admin quests`() {
        assertTrue(AdminFundFactory.deriveAll(state(budgetCap = 0.0)).isEmpty())
        assertTrue(AdminFundFactory.deriveAll(state(budgetCap = -5.0, potOpened = false)).isEmpty())
    }

    @Test
    fun `budget set but pot not opened prompts opening the pot only`() {
        val quests = AdminFundFactory.deriveAll(state(potOpened = false, suggestedAllowance = 20.0))
        assertEquals(listOf(AdminFundFactory.OPEN_POT_ID), quests.map { it.id })
        assertEquals(QuestFrequency.ONE_OFF, quests.single().frequency)
    }

    @Test
    fun `pot opened with nothing earned schedules monthly funding only`() {
        val quests = AdminFundFactory.deriveAll(state(suggestedAllowance = 0.0))
        assertEquals(listOf(AdminFundFactory.FUND_MONTH_ID), quests.map { it.id })
        assertEquals(QuestFrequency.MONTHLY, quests.single().frequency)
    }

    @Test
    fun `pot opened with earned allowance also offers a monthly claim`() {
        val quests = AdminFundFactory.deriveAll(state(suggestedAllowance = 12.5))
        assertEquals(
            listOf(AdminFundFactory.FUND_MONTH_ID, AdminFundFactory.CLAIM_ID),
            quests.map { it.id },
        )
        assertTrue(quests.all { it.frequency == QuestFrequency.MONTHLY })
    }

    @Test
    fun `admin quests are meta-maintenance so the meta cap protects progression`() {
        val all = listOf(
            AdminFundFactory.openPotQuest(),
            AdminFundFactory.fundMonthQuest(),
            AdminFundFactory.claimQuest(),
        )
        assertTrue(all.all { it.category == QuestCategory.META_MAINTENANCE })
        // Stable, namespaced ids so the ledger/scheduler dedup them like any quest.
        assertTrue(all.all { it.id.startsWith(AdminFundFactory.PREFIX) })
    }
}
