package net.estopia.hncrevision.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import net.estopia.hncrevision.logic.Tab

@Composable
fun HncApp(vm: AppViewModel) {
    val tab = vm.snapshot.tab
    var morePage by rememberSaveable { mutableStateOf<MorePage?>(null) }

    BackHandler(enabled = tab == Tab.MORE && morePage != null) { morePage = null }
    BackHandler(enabled = tab != Tab.STUDY && !(tab == Tab.MORE && morePage != null)) { vm.act { setTab(Tab.STUDY) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                TabItem(Tab.STUDY, "Study", AppIcons.Study, tab) { vm.act { setTab(Tab.STUDY) } }
                TabItem(Tab.QUIZ, "Quiz", AppIcons.Quiz, tab) { vm.act { setTab(Tab.QUIZ) } }
                TabItem(Tab.BROWSE, "Browse", AppIcons.Search, tab) { vm.act { setTab(Tab.BROWSE) } }
                TabItem(Tab.MORE, "More", AppIcons.More, tab) {
                    // Tapping More again returns to its menu.
                    if (tab == Tab.MORE) morePage = null
                    vm.act { setTab(Tab.MORE) }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            when (tab) {
                Tab.STUDY -> StudyScreen(vm)
                Tab.QUIZ -> QuizScreen(vm)
                Tab.BROWSE -> BrowseScreen(vm)
                Tab.MORE -> when (morePage) {
                    null -> MoreMenu(vm) { morePage = it }
                    MorePage.OUTCOMES -> OutcomesScreen(vm) { morePage = null }
                    MorePage.VLSM -> VlsmScreen(vm) { morePage = null }
                    MorePage.SETTINGS -> SettingsScreen(vm) { morePage = null }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(item: Tab, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, current: Tab, onClick: () -> Unit) {
    NavigationBarItem(
        selected = item == current,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

/** A screen with its own top bar; the status-bar inset is handled by the TopAppBar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) { content() }
    }
}
