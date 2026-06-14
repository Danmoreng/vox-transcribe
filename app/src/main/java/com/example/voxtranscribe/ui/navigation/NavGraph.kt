package com.example.voxtranscribe.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.voxtranscribe.ui.screens.HomeScreen
import com.example.voxtranscribe.ui.screens.RecordingScreen
import com.example.voxtranscribe.ui.screens.DetailScreen
import com.example.voxtranscribe.ui.screens.GemmaModelScreen
import com.example.voxtranscribe.ui.screens.SetupScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

sealed class Screen(val route: String) {
    object Setup : Screen("setup")
    object SetupPreview : Screen("setup?preview=true")
    object Home : Screen("home")
    object Record : Screen("record")
    object Detail : Screen("detail/{noteId}") {
        fun createRoute(noteId: Long) = "detail/$noteId"
    }
    object GemmaModel : Screen("gemma_model")
}

@Composable
fun VoxNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Setup.route,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(320)
            )
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(320)
            )
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(320)
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(320)
            )
        }
    ) {
        composable(
            route = "setup?preview={preview}",
            arguments = listOf(navArgument("preview") {
                type = NavType.BoolType
                defaultValue = false
            }),
        ) { backStackEntry ->
            val previewMode = backStackEntry.arguments?.getBoolean("preview") ?: false
            SetupScreen(
                previewMode = previewMode,
                onSetupComplete = {
                    if (previewMode) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToRecord = { navController.navigate(Screen.Record.route) },
                onNavigateToDetail = { noteId -> 
                    navController.navigate(Screen.Detail.createRoute(noteId))
                },
                onNavigateToModelSettings = { navController.navigate(Screen.GemmaModel.route) }
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
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Screen.GemmaModel.route) {
            GemmaModelScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSetupPreview = { navController.navigate(Screen.SetupPreview.route) },
            )
        }
    }
}
