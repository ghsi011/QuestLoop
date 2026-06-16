package com.questloop.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

/** Which bottom-nav tab a route belongs to (sub-screens map to their parent tab). */
private fun tabForRoute(route: String?): Dest? = when (route) {
    Dest.TODAY.route, "achievements" -> Dest.TODAY
    Dest.QUESTS.route, "quest-bank" -> Dest.QUESTS
    Dest.REVIEWS.route -> Dest.REVIEWS
    Dest.REWARDS.route -> Dest.REWARDS
    Dest.SETTINGS.route, "habits" -> Dest.SETTINGS
    else -> null // "add" is a modal with no owning tab
}

/** Switch tabs, saving the outgoing tab's stack and restoring the target's. */
private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
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
            Modifier.fillMaxSize(),
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
    val isSubScreen = routeName == "add" || routeName == "habits" ||
        routeName == "achievements" || routeName == "quest-bank"
    val title = when (routeName) {
        "add" -> "Add quest"
        "habits" -> "Habits & goals"
        "achievements" -> "Achievements"
        "quest-bank" -> "Quest bank"
        else -> "QuestLoop"
    }
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
                androidx.compose.material3.FloatingActionButton(onClick = { navController.openOnce("add") }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add quest")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = activeTab == dest,
                        onClick = {
                            if (activeTab == dest) {
                                // Re-tap the current tab: return to its root view (e.g.
                                // Quests -> pop the Quest Bank). No-op if already there.
                                val popped = navController.popBackStack(dest.route, inclusive = false)
                                if (!popped) navController.switchTab(dest.route)
                            } else {
                                navController.switchTab(dest.route)
                            }
                        },
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
        ) {
            composable(Dest.TODAY.route) {
                val vm: TodayViewModel = viewModel(factory = factory)
                TodayScreen(
                    vm,
                    snackbarHostState,
                    onOpenAchievements = { navController.openOnce("achievements") },
                    // Enter the Quests tab first so the Bank lives under that tab
                    // (correct tab highlight + re-tap returns to the Quests list).
                    onOpenQuestBank = { navController.switchTab(Dest.QUESTS.route); navController.openOnce("quest-bank") },
                    onOpenAddQuest = { navController.openOnce("add") },
                )
            }
            composable(Dest.QUESTS.route) {
                val vm: QuestsViewModel = viewModel(factory = factory)
                QuestsScreen(vm, snackbarHostState, onOpenBank = { navController.openOnce("quest-bank") })
            }
            composable("quest-bank") {
                val vm: com.questloop.app.ui.quests.QuestBankViewModel = viewModel(factory = factory)
                com.questloop.app.ui.quests.QuestBankScreen(vm, snackbarHostState)
            }
            composable("achievements") {
                val vm: com.questloop.app.ui.achievements.AchievementsViewModel = viewModel(factory = factory)
                com.questloop.app.ui.achievements.AchievementsScreen(vm)
            }
            composable(Dest.REVIEWS.route) {
                val vm: ReviewViewModel = viewModel(factory = factory)
                ReviewScreen(vm)
            }
            composable(Dest.REWARDS.route) {
                val vm: RewardsViewModel = viewModel(factory = factory)
                RewardsScreen(vm, snackbarHostState)
            }
            composable(Dest.SETTINGS.route) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(
                    vm,
                    onOpenHabits = { navController.openOnce("habits") },
                    snackbarHostState = snackbarHostState,
                )
            }
            composable("habits") {
                val vm: HabitsViewModel = viewModel(factory = factory)
                HabitsScreen(vm)
            }
            composable("add") {
                val vm: AddQuestViewModel = viewModel(factory = factory)
                AddQuestScreen(vm, onDone = { navController.popBackStack() })
            }
        }
    }
}
