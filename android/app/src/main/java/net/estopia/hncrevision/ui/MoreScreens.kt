package net.estopia.hncrevision.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.platform.LocalContext
import net.estopia.hncrevision.system.Reminders
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.estopia.hncrevision.BuildConfig
import net.estopia.hncrevision.data.DataRepository
import net.estopia.hncrevision.data.DataSource
import net.estopia.hncrevision.data.VlsmExercise
import net.estopia.hncrevision.logic.ALL
import net.estopia.hncrevision.logic.CardMode
import net.estopia.hncrevision.logic.GRADED_UNIT
import net.estopia.hncrevision.logic.Tab
import java.text.DateFormat
import java.util.Date

enum class MorePage(val title: String) { OUTCOMES("Outcomes"), VLSM("VLSM practice"), SETTINGS("Settings"), PROGRESS("Progress") }

@Composable
fun MoreMenu(vm: AppViewModel, open: (MorePage) -> Unit) {
    ScreenScaffold(title = "More") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ContentWidth(Modifier.padding(vertical = 12.dp)) {
                HncCard(padding = 0.dp) {
                    Column {
                        MoreRow("Progress", "Streaks, daily activity and progress by unit", AppIcons.Progress) { open(MorePage.PROGRESS) }
                        Divider()
                        MoreRow("Rated cards", "Flashcards you've marked Confident or Revisit", AppIcons.Rated) {
                            vm.act { setCardMode(CardMode.RATED); setTab(Tab.STUDY) }
                        }
                        Divider()
                        MoreRow("Starred cards", "Flashcards you've starred", AppIcons.Star) {
                            vm.act { setCardMode(CardMode.STARRED); setTab(Tab.STUDY) }
                        }
                        Divider()
                        MoreRow("Outcomes", "How your revision matches each unit", AppIcons.Outcomes) { open(MorePage.OUTCOMES) }
                        Divider()
                        MoreRow("VLSM practice", "Subnetting exercises, generated questions and drills", AppIcons.Network) { open(MorePage.VLSM) }
                        Divider()
                        MoreRow("Settings", "Reminder, read aloud, reading mode, data updates, reset", AppIcons.Settings) { open(MorePage.SETTINGS) }
                    }
                }
                Muted("Original revision prompts; use alongside lecturer materials.")
            }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = LocalHncColors.current.line)

@Composable
private fun MoreRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = LocalHncColors.current.card),
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp),
    )
}

// region Outcomes

@Composable
fun OutcomesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s = vm.snapshot
    val data = s.data
    val unitCode = s.filters.unit
    val units = if (unitCode == ALL) data.units else data.units.filter { it.code == unitCode }
    ScreenScaffold(title = "Outcomes", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ContentWidth(Modifier.padding(vertical = 12.dp)) {
                val checked = data.units.firstOrNull()?.let { data.coverage[it.code]?.checked }.orEmpty()
                Eyebrow("Specification check" + if (checked.isNotBlank()) " · $checked" else "")
                Text(
                    "How your revision matches the units",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Mapped against the published outcome statements and knowledge/skills lists. Counts show related revision " +
                        "material, not completion or full syllabus coverage. Practical tasks, written work and lecturer materials " +
                        "remain essential.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "J0J734 is an integrated exam: its rows show examination sections rather than invented learning outcomes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Muted("Showing ${if (unitCode == ALL) "all units" else s.filters.unitLabel}. Change the unit from the filter on Study, Quiz or Browse.")
                units.forEach { u ->
                    val m = data.coverage[u.code] ?: return@forEach
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = LocalHncColors.current.line)
                    Text(
                        "${u.code} · ${u.title}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (m.gaps.isNotBlank()) Text(m.gaps, style = MaterialTheme.typography.bodyMedium)
                    m.source?.let { LinkText("Published specification", it) }
                    Muted("Version ${m.version} · Relevant pages ${m.pages} · Checked ${m.checked}")
                    val graded = u.code == GRADED_UNIT
                    m.outcomes.forEach { o ->
                        val cards = data.cards.count { it.unit == u.code && o.id in it.outcomes }
                        val mcqs = data.questions.count { it.unit == u.code && o.id in it.outcomes }
                        HncCard(shape = RoundedCornerShape(12.dp), padding = 18.dp) {
                            Text(
                                (if (graded) "" else "Outcome ${o.id} · ") + o.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text("$cards related flashcards · $mcqs MCQs", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (o.note.isNotBlank()) Text(o.note, style = MaterialTheme.typography.bodyMedium, color = LocalHncColors.current.note)
                            if (o.practice.isNotBlank()) {
                                Text(
                                    androidx.compose.ui.text.buildAnnotatedString {
                                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                                        append("Apply it: ")
                                        pop()
                                        append(o.practice)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            PlainButton(
                                "Study this ${if (graded) "section" else "outcome"}",
                                { vm.act { studyOutcome(u.code, o.id) } },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Muted("These are original study prompts, not official assessment questions or assessor-approved coverage.")
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// endregion

// region VLSM

enum class VlsmTab(val label: String) { EXERCISES("Exercises"), GENERATE("Generate"), DRILLS("Drills") }

@Composable
fun VlsmScreen(vm: AppViewModel, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(VlsmTab.EXERCISES) }
    ScreenScaffold(title = "VLSM practice", onBack = onBack) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            ContentWidth(Modifier.padding(vertical = 12.dp)) {
                Segmented(VlsmTab.entries, tab, { it.label }, { tab = it })
                when (tab) {
                    VlsmTab.EXERCISES -> VlsmExercises(vm)
                    VlsmTab.GENERATE -> VlsmGenerate(vm)
                    VlsmTab.DRILLS -> VlsmDrill(vm)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** The 1.0 VLSM screen: the site's six exercises. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VlsmExercises(vm: AppViewModel) {
    val exercises = vm.snapshot.data.vlsm
    var selected by rememberSaveable { mutableIntStateOf(0) }
    if (selected !in exercises.indices) selected = 0
    val exercise = exercises.getOrNull(selected)
    var hintsShown by rememberSaveable(selected) { mutableIntStateOf(0) }
    var showAnswer by rememberSaveable(selected) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    if (exercise == null) {
        EmptyState(null, "No VLSM exercises", "Check for updates in Settings.")
        return
    }
    HncCard(padding = 22.dp) {
        Eyebrow("IPv4 subnetting practice")
        Text("Variable Length Subnet Masking", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(
            "Give each LAN the smallest subnet that fits. Allocate largest first, starting at the base network address, " +
                "and break ties in alphabetical order. Use conventional IPv4 subnets with network and broadcast addresses reserved.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
            OutlinedTextField(
                value = "${selected + 1}. ${exercise.title}",
                onValueChange = {},
                readOnly = true,
                label = { Text("Choose a practice question") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                exercises.forEachIndexed { i, e ->
                    DropdownMenuItem(text = { Text("${i + 1}. ${e.title}") }, onClick = { selected = i; menu = false })
                }
            }
        }
        Text("Allocate subnets within ${exercise.parent}.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            exercise.requests.forEach { Text("• ${it.lan} needs ${it.hosts} usable addresses", style = MaterialTheme.typography.bodyLarge) }
        }
        Text(
            "Find each LAN’s network address and prefix, subnet mask, usable host range and broadcast address.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = vm.vlsmWorking[selected].orEmpty(),
            onValueChange = { vm.setVlsmWorking(selected, it) },
            label = { Text("Your working") },
            placeholder = { Text("Start by ordering the LANs by host count, then calculate each block size.") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
            shape = ControlShape,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = LocalHncColors.current.card,
                focusedContainerColor = LocalHncColors.current.card,
            ),
        )
        if (hintsShown > 0) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                exercise.hints.take(hintsShown).forEachIndexed { i, h ->
                    Text("${i + 1}. $h", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        val total = exercise.hints.size
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlainButton(
                if (hintsShown >= total) "All $total hints shown" else "Show hint (${hintsShown + 1} of $total)",
                { hintsShown++ },
                enabled = hintsShown < total,
            )
            PrimaryButton(if (showAnswer) "Hide answer" else "Reveal answer", { showAnswer = !showAnswer })
        }
    }
    if (showAnswer) Solution(exercise)
}

@Composable
internal fun Solution(exercise: VlsmExercise) {
    HncCard(padding = 22.dp) {
        Text("Worked solution", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        exercise.rows.forEachIndexed { i, r ->
            Text(
                "${i + 1}. ${r.lan} needs ${r.hosts} hosts. Allow two reserved addresses, then round ${r.hosts + 2} up to " +
                    "${r.usableCapacity + 2} addresses. Allocate ${r.network}. The next block starts immediately after ${r.broadcast}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SubnetTable(exercise)
    }
}

/** Horizontally scrollable table; each column sizes to its widest cell. */
@Composable
private fun SubnetTable(exercise: VlsmExercise) {
    val headings = listOf("LAN", "Hosts needed", "Network / prefix", "Subnet mask", "First usable", "Last usable", "Broadcast", "Usable capacity")
    val line = LocalHncColors.current.line
    Box(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, line), RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Subnet allocation table, scrolls sideways" },
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            headings.forEachIndexed { col, heading ->
                Column(Modifier.width(IntrinsicSize.Max)) {
                    TableCell(heading, header = true)
                    exercise.rows.forEach { row ->
                        HorizontalDivider(color = line, modifier = Modifier.fillMaxWidth())
                        TableCell(row.cells[col], header = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, header: Boolean) {
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Visible,
        softWrap = false,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
    )
}

// endregion

// region Settings

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s = vm.snapshot
    var confirmReset by remember { mutableStateOf(false) }
    ScreenScaffold(title = "Settings", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ContentWidth(Modifier.padding(vertical = 12.dp)) {
                Eyebrow("Reminder", Modifier.semantics { heading() })
                ReminderSettingsCard(vm)

                Eyebrow("Accessibility", Modifier.padding(top = 8.dp).semantics { heading() })
                HncCard(padding = 0.dp) {
                    Column {
                        SwitchRow(
                            "Read flashcards aloud",
                            "Reads the question, then the answer when revealed and the hint when shown. Uses the system voice (English, UK).",
                            vm.readAloud,
                        ) { vm.updateReadAloud(it) }
                        Divider()
                        SwitchRow(
                            "Dyslexia reading mode",
                            "A plain sans font with extra letter and line spacing on a softer background.",
                            vm.dyslexiaReading,
                        ) { vm.updateDyslexiaReading(it) }
                    }
                }

                Eyebrow("Revision data", Modifier.padding(top = 8.dp).semantics { heading() })
                HncCard {
                    Text(sourceText(vm.source), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${s.data.units.size} units · ${s.totalCards} flashcards · ${s.totalQuestions} quiz questions",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s.lastChange?.let { c ->
                        Muted(
                            "Last change: ${c.added} added, ${c.updated} updated" +
                                (if (c.questionsChanged > 0) ", ${c.questionsChanged} quiz ${if (c.questionsChanged == 1) "question" else "questions"} changed" else "") +
                                " · ${formatDay(c.date)}",
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlainButton("Check for updates", { vm.refresh(manual = true) }, enabled = !vm.refreshing)
                        if (vm.refreshing) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    when (vm.manualRefreshOutcome) {
                        RefreshOutcome.UPDATED -> Muted("Up to date with the website.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        RefreshOutcome.FAILED -> Muted(
                            "Couldn’t reach the website. You can keep revising with the copy on this device.",
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        null -> Unit
                    }
                }

                Eyebrow("Progress", Modifier.padding(top = 8.dp).semantics { heading() })
                HncCard {
                    Text(
                        "Ratings, review schedules, quiz answers, stars, notes and VLSM working are saved on this device only.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PlainButton("Reset saved progress", { confirmReset = true })
                }

                Eyebrow("About", Modifier.padding(top = 8.dp).semantics { heading() })
                HncCard {
                    Text(
                        "Original revision prompts; use alongside lecturer materials. Content from the HNC Revision Studio website.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinkText("HNC Revision Studio website", DataRepository.SITE_URL)
                    Muted("HNC Revision ${BuildConfig.VERSION_NAME}")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (confirmReset) {
        var alsoStars by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset saved progress?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Clear all flashcard ratings and saved quiz answers? This resets ${s.totalCards} flashcards and " +
                            "${s.totalQuestions} quiz questions on this device.",
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = alsoStars, role = Role.Checkbox) { alsoStars = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoStars, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Also delete stars and notes")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.resetProgress(alsoStars); confirmReset = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Daily reminder toggle and time. Turning it on asks for notification permission on Android 13+;
 * if that's refused (or notifications are blocked) the toggle turns back off with a way into
 * system settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSettingsCard(vm: AppViewModel) {
    val context = LocalContext.current
    val reminder = vm.reminder
    var showDenied by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Reminders.canNotify(context)) {
            vm.updateReminder(vm.reminder.copy(enabled = true))
        } else {
            vm.updateReminder(vm.reminder.copy(enabled = false))
            showDenied = true
        }
    }
    val turnOn = {
        when {
            Reminders.canNotify(context) -> vm.updateReminder(reminder.copy(enabled = true))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> showDenied = true // Notifications blocked in system settings.
        }
    }
    HncCard(padding = 0.dp) {
        Column {
            SwitchRow(
                "Daily reminder",
                "A notification at ${reminder.label} with how many cards are due and your streak.",
                reminder.enabled,
            ) { on -> if (on) turnOn() else vm.updateReminder(reminder.copy(enabled = false)) }
            if (reminder.enabled) {
                Divider()
                ListItem(
                    headlineContent = { Text("Reminder time", fontWeight = FontWeight.SemiBold) },
                    trailingContent = { Text(reminder.label, style = MaterialTheme.typography.titleMedium) },
                    colors = ListItemDefaults.colors(containerColor = LocalHncColors.current.card),
                    modifier = Modifier
                        .clickable(role = Role.Button, onClickLabel = "Change reminder time") { pickTime = true }
                        .heightIn(min = 56.dp),
                )
                if (!Reminders.canNotify(context)) {
                    Muted(
                        "Notifications are turned off for HNC Revision in system settings.",
                        Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(initialHour = reminder.hour, initialMinute = reminder.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateReminder(reminder.copy(hour = state.hour, minute = state.minute))
                    pickTime = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
    if (showDenied) {
        AlertDialog(
            onDismissRequest = { showDenied = false },
            title = { Text("Notifications are off") },
            text = {
                Text(
                    "HNC Revision needs permission to send notifications for the daily reminder. " +
                        "You can allow notifications in system settings, then turn the reminder on again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDenied = false
                    openNotificationSettings(context)
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showDenied = false }) { Text("Not now") } },
        )
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Muted(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun sourceText(source: DataSource): String {
    return when (source) {
        is DataSource.Live -> "Live from the website · updated " + DateUtils.getRelativeTimeSpanString(
            source.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        ).toString().let { if (it == "0 minutes ago" || it == "in 0 minutes") "just now" else it }
        is DataSource.Saved -> "Saved copy from " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(source.at)) + " (offline)"
        DataSource.BuiltIn -> "Built-in copy (offline)"
    }
}

// endregion
