package com.questloop.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.add.AddQuestScreen
import com.questloop.app.ui.add.AddQuestViewModel
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
    REVIEWS("reviews", "Reviews", Icons.Filled.BarChart),
    REWARDS("rewards", "Rewards", Icons.Filled.CardGiftcard),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestLoopApp(repository: QuestRepository) {
    val navController = rememberNavController()
    val factory = remember(repository) { appViewModelFactory(repository) }
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    Scaffold(
        topBar = { TopAppBar(title = { Text("QuestLoop") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(onClick = { navController.navigate("add") }) {
                Icon(Icons.Filled.Add, contentDescription = "Add quest")
            }
        },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { dest ->
                    val selected = currentRoute?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
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
                TodayScreen(vm, snackbarHostState)
            }
            composable(Dest.REVIEWS.route) {
                val vm: ReviewViewModel = viewModel(factory = factory)
                ReviewScreen(vm)
            }
            composable(Dest.REWARDS.route) {
                val vm: RewardsViewModel = viewModel(factory = factory)
                RewardsScreen(vm)
            }
            composable(Dest.SETTINGS.route) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(vm)
            }
            composable("add") {
                val vm: AddQuestViewModel = viewModel(factory = factory)
                AddQuestScreen(vm, onDone = { navController.popBackStack() })
            }
        }
    }
}
