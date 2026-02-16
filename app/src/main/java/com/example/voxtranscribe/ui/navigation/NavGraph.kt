package com.example.voxtranscribe.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.voxtranscribe.ui.screens.HomeScreen
import com.example.voxtranscribe.ui.screens.RecordingScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Record : Screen("record")
}

@Composable
fun VoxNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToRecord = { navController.navigate(Screen.Record.route) }
            )
        }
        composable(Screen.Record.route) {
            RecordingScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
