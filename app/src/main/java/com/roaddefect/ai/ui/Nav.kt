package com.roaddefect.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roaddefect.ai.AppContainer
import com.roaddefect.ai.inspection.Phase

object Routes {
    const val HOME = "home"
    const val SETUP = "setup"
    const val INSPECTION = "inspection"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val VIDEO = "video"
    const val SUMMARY = "summary/{id}"
    const val MAP = "map/{id}"
    fun summary(id: Long) = "summary/$id"
    fun map(id: Long) = "map/$id"
}

@Composable
fun AppNav(c: AppContainer) {
    val nav = rememberNavController()
    val state by c.controller.state.collectAsState()
    // If an inspection is still running when the Activity is recreated, go straight back to it.
    val start = if (state.phase != Phase.IDLE) Routes.INSPECTION else Routes.HOME
    val idArg = listOf(navArgument("id") { type = NavType.LongType })

    NavHost(nav, startDestination = start) {
        composable(Routes.HOME) {
            HomeScreen(
                c,
                onStart = { nav.navigate(Routes.SETUP) },
                onReturnToInspection = { nav.navigate(Routes.INSPECTION) },
                onHistory = { nav.navigate(Routes.HISTORY) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onValidateVideo = { nav.navigate(Routes.VIDEO) },
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                c,
                onBack = { nav.popBackStack() },
                onStarted = { nav.navigate(Routes.INSPECTION) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.INSPECTION) {
            InspectionScreen(
                c,
                onFinished = { id -> nav.navigate(Routes.summary(id)) { popUpTo(Routes.HOME) } },
                onFailed = { nav.popBackStack(Routes.HOME, false) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(c, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.summary(it)) })
        }
        composable(Routes.SUMMARY, arguments = idArg) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            SummaryScreen(c, id, onBack = { nav.popBackStack() }, onMap = { nav.navigate(Routes.map(id)) })
        }
        composable(Routes.MAP, arguments = idArg) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            MapScreen(c, id, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) { SettingsScreen(c, onBack = { nav.popBackStack() }) }
        composable(Routes.VIDEO) { VideoValidationScreen(c, onBack = { nav.popBackStack() }) }
    }
}
