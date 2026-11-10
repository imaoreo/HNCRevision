package net.estopia.hncrevision.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import net.estopia.hncrevision.data.Reference
import net.estopia.hncrevision.logic.ALL
import net.estopia.hncrevision.logic.FilterView
import net.estopia.hncrevision.logic.Stats
import net.estopia.hncrevision.logic.ProgressStore
import net.estopia.hncrevision.logic.TrackerView
import net.estopia.hncrevision.logic.streakLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val CardShape = RoundedCornerShape(20.dp)
val ControlShape = RoundedCornerShape(10.dp)
val MaxContentWidth = 720.dp

/** Opens a link in a Custom Tab, falling back to any browser. */
fun openUrl(context: Context, url: String, toolbarColor: Int) {
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
            .build()
            .launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No browser available to open this link", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun rememberOpenUrl(): (String) -> Unit {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.background.toArgb()
    return { url -> openUrl(context, url, color) }
}

/** Centres content and caps its width on tablets. */
@Composable
fun ContentWidth(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** White card, 20dp corners, hairline border (the site's `.card`). */
@Composable
fun HncCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = CardShape,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val extra = LocalHncColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = extra.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, extra.line),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = LocalHncColors.current.muted,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = LocalHncColors.current.muted)
}

@Composable
fun Tag(text: String) {
    val extra = LocalHncColors.current
    Text(
        text.uppercase(),
        modifier = Modifier
            .background(extra.tagBackground, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = extra.tagText,
    )
}

/** Card meta line: tag plus small upper-case details, wrapping on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaRow(tag: String, vararg details: String) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Tag(tag)
        details.forEach { Eyebrow(it) }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = ControlShape,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = ControlShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun PlainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = ControlShape,
        border = BorderStroke(1.dp, LocalHncColors.current.line),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = LocalHncColors.current.card,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** A link that opens in the browser, labelled for TalkBack. */
@Composable
fun LinkText(label: String, url: String, modifier: Modifier = Modifier) {
    val open = rememberOpenUrl()
    Text(
        "$label ↗",
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open in browser") { open(url) }
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp)
            .semantics { contentDescription = "$label, link" },
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Medium,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun ReferencesList(references: List<Reference>) {
    if (references.isEmpty()) return
    Column {
        Text(
            "References",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        references.forEach { LinkText(it.label, it.url) }
    }
}

/** "Flashcards · x seen · y unseen this round", expanding to list the questions. */
@Composable
fun Tracker(tracker: TrackerView) {
    var open by rememberSaveable(tracker.kind) { mutableStateOf(false) }
    val extra = LocalHncColors.current
    Surface(
        shape = ControlShape,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, extra.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (open) "Hide list" else "Show list") { open = !open }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tracker.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    TrackerGroup("Unseen", tracker.unseen)
                    Spacer(Modifier.height(10.dp))
                    TrackerGroup("Seen", tracker.seen)
                }
            }
        }
    }
}

@Composable
private fun TrackerGroup(label: String, items: List<String>) {
    Text("$label (${items.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    if (items.isEmpty()) {
        Muted("None")
    } else {
        // The site caps this list at ~14rem; show the first entries and a count of the rest.
        val shown = items.take(40)
        shown.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
        if (items.size > shown.size) Muted("…and ${items.size - shown.size} more")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatLine(stats: Stats, streak: Int = 0) {
    val streakText = streakLabel(streak)
    val text = "${stats.flashcards} flashcards · ${stats.confident} confident · ${stats.revisit} to revisit · ${stats.due} due" +
        (if (streak >= 1) " · $streak-day streak" else "")
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = text },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatItem(stats.flashcards, "flashcards")
            StatItem(stats.confident, "confident")
            StatItem(stats.revisit, "to revisit")
            StatItem(stats.due, "due")
        }
        if (streakText != null) Text(streakText, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun StatItem(value: Int, label: String) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        Muted(label)
    }
}

/**
 * Compact filter bar shared by Study, Browse and Quiz: unit/outcome button (opens a sheet),
 * topic chips and the stat line.
 */
@Composable
fun FilterBar(filters: FilterView, stats: Stats, vm: AppViewModel) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val extra = LocalHncColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            onClick = { sheetOpen = true },
            shape = ControlShape,
            color = extra.card,
            border = BorderStroke(1.dp, extra.line),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Unit: ${filters.unitLabel}. ${filters.outcomeLabel}. Change unit or outcome"
                },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(filters.unitLabel, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Muted(filters.outcomeLabel)
                }
                Spacer(Modifier.width(8.dp))
                Icon(AppIcons.Filter, contentDescription = null, tint = extra.muted)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filters.topics, key = { it.first }) { (name, count) ->
                val selected = name == filters.topic
                FilterChip(
                    selected = selected,
                    onClick = { vm.act { setTopic(name) } },
                    label = { Text("$name  $count") },
                    modifier = Modifier.semantics { contentDescription = "$name, $count cards" },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
        StatLine(stats, vm.snapshot.streak)
    }
    if (sheetOpen) FilterSheet(filters, vm) { sheetOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(filters: FilterView, vm: AppViewModel, onDismiss: () -> Unit) {
    val units = vm.snapshot.data.units
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Eyebrow("Your unit", Modifier.semantics { heading() })
            Column(Modifier.selectableGroup()) {
                ChoiceRow("All units", filters.unit == ALL) { vm.act { setUnit(ALL) } }
                units.forEach { u -> ChoiceRow("${u.code} · ${u.title}", filters.unit == u.code) { vm.act { setUnit(u.code) } } }
            }
            Spacer(Modifier.height(4.dp))
            Text(filters.unitInfo, style = MaterialTheme.typography.bodyMedium)
            filters.unitSource?.let { LinkText("Unit specification", it) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = LocalHncColors.current.line)
            Eyebrow("Outcome / section", Modifier.semantics { heading() })
            if (!filters.outcomeEnabled) Muted("Choose a unit to filter by outcome.", Modifier.padding(top = 6.dp))
            Column(Modifier.selectableGroup()) {
                filters.outcomes.forEach { (id, label) ->
                    ChoiceRow(label, filters.outcome == id, enabled = filters.outcomeEnabled) { vm.act { setOutcome(id) } }
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Done", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalHncColors.current.muted,
        )
    }
}

@Composable
fun EmptyState(eyebrow: String?, title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    HncCard(padding = 28.dp) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            eyebrow?.let { Eyebrow(it) }
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalHncColors.current.muted)
            action?.let { (label, onClick) -> PrimaryButton(label, onClick) }
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val extra = LocalHncColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(extra.progressTrack, RoundedCornerShape(5.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .background(extra.progress, RoundedCornerShape(5.dp)),
        )
    }
}

@Composable
fun ReadAloudRow(vm: AppViewModel, text: String) {
    if (!vm.readAloud) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconTextButton("Read aloud", AppIcons.Speaker) { vm.readNow(text) }
        IconTextButton("Stop reading", AppIcons.Stop) { vm.stopReading() }
    }
}

@Composable
fun IconTextButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = ControlShape,
        border = BorderStroke(1.dp, LocalHncColors.current.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val WeekdayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/** "12 Nov". */
fun formatDay(date: LocalDate): String = date.format(DayFormat)

/** "Thu 12 Nov". */
fun formatWeekday(date: LocalDate): String = date.format(WeekdayFormat)

/** ☆ / ★ toggle, labelled "Star card" / "Unstar card" for TalkBack. */
@Composable
fun StarButton(starred: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val extra = LocalHncColors.current
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            if (starred) AppIcons.Star else AppIcons.StarOutline,
            contentDescription = if (starred) "Unstar card" else "Star card",
            tint = if (starred) extra.star else extra.muted,
        )
    }
}

/** "My note": the saved note with Edit, or "Add a note"; both open the editor dialog. */
@Composable
fun NoteSection(note: String?, onSave: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("My note", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = LocalHncColors.current.note)
            IconTextButton("Edit note", AppIcons.Edit) { editing = true }
        } else {
            IconTextButton("Add a note", AppIcons.Edit) { editing = true }
        }
    }
    if (editing) {
        NoteDialog(
            initial = note.orEmpty(),
            canDelete = note != null,
            onSave = { onSave(it); editing = false },
            onDelete = { onSave(""); editing = false },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun NoteDialog(initial: String, canDelete: Boolean, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val limit = ProgressStore.NOTE_LIMIT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(limit) },
                label = { Text("Note for this card") },
                minLines = 4,
                maxLines = 10,
                supportingText = { Text("${text.length} / $limit") },
                modifier = Modifier.fillMaxWidth(),
                shape = ControlShape,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Soft accent banner with an action and a close button. */
@Composable
fun Banner(text: String, action: String, onAction: () -> Unit, closeLabel: String, onClose: () -> Unit) {
    val extra = LocalHncColors.current
    Surface(
        shape = ControlShape,
        color = extra.tagBackground,
        border = BorderStroke(1.dp, extra.line),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                PrimaryButton(action, onAction)
            }
            IconButton(onClick = onClose) { Icon(AppIcons.Close, contentDescription = closeLabel) }
        }
    }
}

/** Material segmented control in the app's colours, with labels that wrap at large font sizes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) { Text(label(option), maxLines = 2, textAlign = TextAlign.Center) }
        }
    }
}
