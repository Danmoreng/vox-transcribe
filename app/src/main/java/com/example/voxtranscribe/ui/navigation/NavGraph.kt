package com.example.voxtranscribe.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.voxtranscribe.ui.screens.HomeScreen
import com.example.voxtranscribe.ui.screens.RecordingScreen

import com.example.voxtranscribe.ui.screens.HomeScreen
import com.example.voxtranscribe.ui.screens.RecordingScreen
import com.example.voxtranscribe.ui.screens.DetailScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Record : Screen("record")
    object Detail : Screen("detail/{noteId}") {
        fun createRoute(noteId: Long) = "detail/$noteId"
    }
}

@Composable
fun VoxNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToRecord = { navController.navigate(Screen.Record.route) },
                onNavigateToDetail = { noteId -> 
                    navController.navigate(Screen.Detail.createRoute(noteId))
                }
            )
        }
        composable(Screen.Record.route) {
            RecordingScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Detail.route,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType })
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getLong("noteId") ?: return@composable
            DetailScreen(
                noteId = noteId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

