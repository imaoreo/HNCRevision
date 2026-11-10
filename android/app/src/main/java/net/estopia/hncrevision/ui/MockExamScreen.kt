package net.estopia.hncrevision.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.estopia.hncrevision.logic.MockExam
import net.estopia.hncrevision.logic.MockHistoryEntry
import net.estopia.hncrevision.logic.MockResultView
import net.estopia.hncrevision.logic.MockRunView
import net.estopia.hncrevision.logic.MockSetupView
import net.estopia.hncrevision.logic.MockView
import java.time.LocalDateTime

/** Quiz → Mock exam: setup (with history), an attempt in progress, or the last results. */
@Composable
fun MockExam(mock: MockView, vm: AppViewModel) {
    when {
        mock.run != null -> MockRun(mock.run, vm)
        mock.result != null -> MockResults(mock.result, vm)
        else -> MockSetup(mock.setup, vm)
    }
}

// region Setup

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockSetup(setup: MockSetupView, vm: AppViewModel) {
    var units by rememberSaveable(setup.defaultUnits) { mutableStateOf(setup.defaultUnits.toList()) }
    var count by rememberSaveable { mutableIntStateOf(20) }
    var timed by rememberSaveable { mutableStateOf(true) }
    val available = setup.units.filter { it.unit.code in units }.sumOf { it.available }
    val actual = minOf(count, available)

    HncCard(padding = 22.dp) {
        Eyebrow("Mock exam")
        Text("Set up a mock exam", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Muted("Random questions from the units you choose, with no feedback until you submit.")

        Text("Units", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Column {
            setup.units.forEach { option ->
                val code = option.unit.code
                val checked = code in units
                val enabled = option.available > 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { on ->
                            units = if (on) units + code else units - code
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "$code · ${option.unit.title} (${option.available})",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalHncColors.current.muted,
                    )
                }
            }
        }

        Text("Questions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Segmented(MockExam.COUNTS, count, { it.toString() }, { count = it })
        Muted(
            if (available < count) "$actual questions ($available available)" else "$count questions ($available available)",
        )

        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = timed, role = Role.Switch) { timed = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Timer", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Muted("90 seconds per question" + if (actual > 0) " (${MockExam.formatDuration(actual * 90L)} in total)" else "")
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = timed, onCheckedChange = null)
        }

        PrimaryButton(
            "Start",
            { vm.act { startMock(units, count, timed) } },
            Modifier.fillMaxWidth(),
            enabled = actual > 0,
        )
        HorizontalDivider(color = LocalHncColors.current.line)
        PlainButton(
            "Graded Unit Section 1 style",
            { vm.act { startMockPreset() } },
            Modifier.fillMaxWidth(),
            enabled = setup.presetAvailable > 0,
        )
        Muted(
            "5 random questions each from J0H934, J0HL34, J0HJ34 and J0J734 (20 in total, 30 minutes). " +
                "Practice in the style of the Graded Unit exam's selected-response section. Not an official paper.",
        )
    }
    if (setup.history.isNotEmpty()) {
        Eyebrow("Recent attempts", Modifier.padding(top = 4.dp).semantics { heading() })
        HncCard(padding = 0.dp) {
            Column {
                setup.history.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider(color = LocalHncColors.current.line)
                    HistoryRow(h)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(h: MockHistoryEntry) {
    val date = try {
        formatDay(LocalDateTime.parse(h.date).toLocalDate())
    } catch (_: Exception) {
        h.date
    }
    val text = "$date · ${h.correct}/${h.total} · ${h.percent}%"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Muted(h.units.joinToString(", ") + " · " + MockExam.formatDuration(h.durationSeconds))
        }
    }
}

// endregion

// region Attempt in progress

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockRun(run: MockRunView, vm: AppViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmSubmit by rememberSaveable { mutableStateOf(false) }
    val deadline = run.deadline
    if (deadline != null) {
        LaunchedEffect(deadline) {
            while (true) {
                now = System.currentTimeMillis()
                if (now >= deadline) {
                    vm.act { tick() }
                    break
                }
                delay(1000 - now % 1000)
            }
        }
    }
    val remaining = deadline?.let { maxOf(0L, (it - now + 999) / 1000) }
    val q = run.current
    val extra = LocalHncColors.current

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Question ${run.index + 1} of ${run.total}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (remaining != null) {
            val low = remaining <= 60
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Time remaining ${remaining / 60} minutes ${remaining % 60} seconds"
                },
            ) {
                Icon(AppIcons.Timer, contentDescription = null, tint = if (low) extra.wrongBorder else extra.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    MockExam.formatDuration(remaining),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (low) extra.wrongBorder else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    Muted("${run.total - run.unanswered} answered · ${run.unanswered} to go. Answers can be changed until you submit.")

    HncCard(padding = 22.dp) {
        MetaRow(q.tag, q.meta)
        Text(q.question.q, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            q.options.forEach { option ->
                OptionRow(q, option.original, "${option.letter}.", option.text) { vm.act { mockChoose(option.original) } }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlainButton("← Previous", { vm.act { mockGoTo(run.index - 1) } }, Modifier.weight(1f), enabled = run.index > 0)
        PlainButton("Next →", { vm.act { mockGoTo(run.index + 1) } }, Modifier.weight(1f), enabled = !run.isLast)
    }

    Eyebrow("Jump to a question", Modifier.semantics { heading() })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        run.answered.forEachIndexed { i, answered ->
            QuestionChip(i + 1, answered, current = i == run.index) { vm.act { mockGoTo(i) } }
        }
    }
    PrimaryButton(
        "Submit",
        { if (run.unanswered > 0) confirmSubmit = true else vm.act { mockSubmit() } },
        Modifier.fillMaxWidth(),
    )
    if (confirmSubmit) {
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text("Submit now?") },
            text = {
                Text(
                    "${run.unanswered} ${if (run.unanswered == 1) "question is" else "questions are"} unanswered. " +
                        "Unanswered questions count as incorrect.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmSubmit = false; vm.act { mockSubmit() } }) { Text("Submit") } },
            dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun QuestionChip(number: Int, answered: Boolean, current: Boolean, onClick: () -> Unit) {
    val extra = LocalHncColors.current
    Surface(
        onClick = onClick,
        shape = ControlShape,
        color = if (answered) MaterialTheme.colorScheme.secondaryContainer else extra.card,
        contentColor = if (answered) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (current) 2.dp else 1.dp, if (current) MaterialTheme.colorScheme.onSurface else extra.line),
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = "Question $number"
                stateDescription = listOfNotNull(if (answered) "answered" else "not answered", if (current) "current" else null).joinToString(", ")
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text(number.toString(), fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
        }
    }
}

// endregion

// region Results

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockResults(r: MockResultView, vm: AppViewModel) {
    HncCard(padding = 22.dp) {
        Eyebrow("Mock exam results")
        Text(
            "${r.correct} / ${r.total} (${r.percent}%)",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Time taken ${MockExam.formatDuration(r.durationSeconds)}" + if (r.timedOut) " · Time ran out" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = LocalHncColors.current.muted,
        )
        Column {
            r.perUnit.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = LocalHncColors.current.line)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 8.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${row.score.unit} · ${row.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${row.score.correct} / ${row.score.asked} · ${row.score.percent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Muted("This is a revision score, not an official unit grade.")
        PrimaryButton("New mock exam", { vm.act { closeMock() } })
    }
    Eyebrow("Review", Modifier.semantics { heading() })
    r.items.forEach { item -> ResultRow(item) { vm.act { toggleStar(item.question.cardId) } } }
}

// endregion
