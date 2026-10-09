package eu.elraro.babytracker.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.ui.settings.SettingsScreen
import eu.elraro.babytracker.ui.stats.StatsScreen
import eu.elraro.babytracker.ui.today.TodayScreen
import kotlinx.serialization.Serializable

@Serializable
private data object TodayRoute

@Serializable
private data object StatisticsRoute

@Serializable
private data object SettingsRoute

private data class Tab(
    val route: Any,
    @param:StringRes val label: Int,
    val icon: ImageVector,
)

private val tabs =
    listOf(
        Tab(TodayRoute, R.string.nav_today, Icons.Outlined.Today),
        Tab(StatisticsRoute, R.string.nav_statistics, Icons.Outlined.BarChart),
        Tab(SettingsRoute, R.string.nav_settings, Icons.Outlined.Settings),
    )

/** The signed-in app: three top-level sections behind a bottom bar. */
@Composable
fun HomeScreen(account: Account) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        // The label below names the tab; the icon adds nothing for a screen reader.
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TodayRoute,
            // Consumed so that screens adding keyboard padding do not also pad for the bottom bar.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable<TodayRoute> {
                TodayScreen()
            }
            composable<StatisticsRoute> {
                StatsScreen()
            }
            composable<SettingsRoute> {
                SettingsScreen(account = account)
            }
        }
    }
}
