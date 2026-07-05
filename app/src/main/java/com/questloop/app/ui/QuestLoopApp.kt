package com.questloop.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.questloop.app.reminders.ReminderScheduler
import com.questloop.app.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.add.AddQuestScreen
import com.questloop.app.ui.add.AddQuestViewModel
import com.questloop.app.ui.habits.HabitsScreen
import com.questloop.app.ui.habits.HabitsViewModel
import com.questloop.app.ui.quests.QuestsScreen
import com.questloop.app.ui.quests.QuestsViewModel
import com.questloop.app.ui.review.ReviewScreen
import com.questloop.app.ui.review.ReviewViewModel
import com.questloop.app.ui.rewards.RewardsScreen
import com.questloop.app.ui.rewards.RewardsViewModel
import com.questloop.app.ui.settings.SettingsScreen
import com.questloop.app.ui.settings.SettingsViewModel
import com.questloop.app.ui.today.TodayScreen
import com.questloop.app.ui.today.TodayViewModel

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Today", Icons.Filled.Home),
    QUESTS("quests", "Quests", Icons.Filled.Checklist),
    REVIEWS("reviews", "Reviews", Icons.Filled.BarChart),
    REWARDS("rewards", "Rewards", Icons.Filled.CardGiftcard),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/**
 * Sub-screens/modals reachable from the tabs. Single registry of their routes:
 * it drives the top-bar title, the back arrow, and which tab stays lit
 * (`owningTab == null` = modal with no tab). Adding a sub-screen is one entry
 * here plus its `composable` in the NavHost.
 */
private enum class SubScreen(val route: String, val title: String, val owningTab: Dest?) {
    ADD("add", "Add quest", owningTab = null),
    HABITS("habits", "Habits & goals", Dest.SETTINGS),
    ACHIEVEMENTS("achievements", "Achievements", Dest.TODAY),
    QUEST_BANK("quest-bank", "Quest bank", Dest.QUESTS),
    COMPLETED("completed", "Completed quests", Dest.REVIEWS),
    ;

    companion object {
        fun forRoute(route: String?): SubScreen? = entries.firstOrNull { it.route == route }
    }
}

/** Which bottom-nav tab a route belongs to (sub-screens map to their owning tab). */
private fun tabForRoute(route: String?): Dest? =
    Dest.entries.firstOrNull { it.route == route } ?: SubScreen.forRoute(route)?.owningTab

/**
 * Switch tabs, always landing on the tab's ROOT screen. Any sub-screen open under
 * a tab (quest bank, add, habits, …) is popped and is NOT restored when you come
 * back — re-selecting a tab is a clean reset to its main view. (Draft input that
 * should survive, e.g. a half-typed quest, is persisted in its ViewModel, not the
 * back stack.)
 */
private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = false }
        launchSingleTop = true
        restoreState = false
    }
}

/** Open a sub-screen/modal once (no duplicate back-stack entries on rapid taps). */
private fun NavController.openOnce(route: String) {
    navigate(route) { launchSingleTop = true }
}

@Composable
fun QuestLoopApp(repository: QuestRepository) {
    // First-run gate: show onboarding until the user taps "Get started".
    var onboarded by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { onboarded = repository.isOnboardingComplete() }

    when (onboarded) {
        null -> androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { androidx.compose.material3.CircularProgressIndicator() }
        false -> OnboardingScreen(
            onGetStarted = { scope.launch { repository.completeOnboarding(); onboarded = true } },
        )
        true -> QuestLoopMain(repository)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestLoopMain(repository: QuestRepository) {
    val navController = rememberNavController()
    val factory = remember(repository) { appViewModelFactory(repository) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-arm reminder alarms on launch (a BootReceiver also restores them after reboot).
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        runCatching { ReminderScheduler(context).apply(repository.reminderConfig()) }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val routeName = currentRoute?.route
    val subScreen = SubScreen.forRoute(routeName)
    val isSubScreen = subScreen != null
    val title = subScreen?.title ?: "QuestLoop"
    // The tab a (possibly sub-) screen belongs to, so the right tab stays lit and
    // re-tapping it returns to that tab's root. "add" is a modal (no tab).
    val activeTab = tabForRoute(routeName)
    // FAB (add a quest) only on the two list roots, not on sub-screens.
    val showFab = routeName == Dest.TODAY.route || routeName == Dest.QUESTS.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (isSubScreen) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (showFab) {
                androidx.compose.material3.FloatingActionButton(onClick = { navController.openOnce(SubScreen.ADD.route) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add quest")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = activeTab == dest,
                        // Always go to the tab's root, whether or not it's the active tab.
                        onClick = { navController.switchTab(dest.route) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.TODAY.route,
            modifier = Modifier.padding(padding),
            // A consistent gentle crossfade reads smoother than the default slide.
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(180)) },
        ) {
            composable(Dest.TODAY.route) {
                val vm: TodayViewModel = viewModel(factory = factory)
                TodayScreen(
                    vm,
                    snackbarHostState,
                    onOpenAchievements = { navController.openOnce(SubScreen.ACHIEVEMENTS.route) },
                    // Open the Bank in one navigation (no tab-switch underneath, so
                    // back returns to Today). tabForRoute lights the Quests tab; a
                    // tab tap still resets cleanly to the Quests root.
                    onOpenQuestBank = { navController.openOnce(SubScreen.QUEST_BANK.route) },
                    onOpenAddQuest = { navController.openOnce(SubScreen.ADD.route) },
                )
            }
            composable(Dest.QUESTS.route) {
                val vm: QuestsViewModel = viewModel(factory = factory)
                QuestsScreen(vm, snackbarHostState, onOpenBank = { navController.openOnce(SubScreen.QUEST_BANK.route) })
            }
            composable(SubScreen.QUEST_BANK.route) {
                val vm: com.questloop.app.ui.quests.QuestBankViewModel = viewModel(factory = factory)
                com.questloop.app.ui.quests.QuestBankScreen(vm, snackbarHostState)
            }
            composable(SubScreen.ACHIEVEMENTS.route) {
                val vm: com.questloop.app.ui.achievements.AchievementsViewModel = viewModel(factory = factory)
                com.questloop.app.ui.achievements.AchievementsScreen(vm)
            }
            composable(Dest.REVIEWS.route) {
                val vm: ReviewViewModel = viewModel(factory = factory)
                ReviewScreen(vm, onOpenCompleted = { navController.openOnce(SubScreen.COMPLETED.route) })
            }
            composable(SubScreen.COMPLETED.route) {
                val vm: com.questloop.app.ui.completed.CompletedViewModel = viewModel(factory = factory)
                com.questloop.app.ui.completed.CompletedScreen(vm, snackbarHostState)
            }
            composable(Dest.REWARDS.route) {
                val vm: RewardsViewModel = viewModel(factory = factory)
                RewardsScreen(vm, snackbarHostState)
            }
            composable(Dest.SETTINGS.route) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(
                    vm,
                    onOpenHabits = { navController.openOnce(SubScreen.HABITS.route) },
                    snackbarHostState = snackbarHostState,
                )
            }
            composable(SubScreen.HABITS.route) {
                val vm: HabitsViewModel = viewModel(factory = factory)
                HabitsScreen(vm)
            }
            composable(SubScreen.ADD.route) {
                val vm: AddQuestViewModel = viewModel(factory = factory)
                AddQuestScreen(vm, onDone = { navController.popBackStack() })
            }
        }
    }
}
