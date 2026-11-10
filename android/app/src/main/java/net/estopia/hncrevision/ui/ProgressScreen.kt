package net.estopia.hncrevision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.estopia.hncrevision.logic.UnitProgress
import net.estopia.hncrevision.logic.percent
import net.estopia.hncrevision.logic.streakLabel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** More → Progress: streaks, a 14-day activity chart and per-unit progress. */
@Composable
fun ProgressScreen(vm: AppViewModel, onBack: () -> Unit) {
    val p = vm.snapshot.progress
    ScreenScaffold(title = "Progress", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ContentWidth(Modifier.padding(vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StreakCard("Current streak", p.streak, Modifier.weight(1f))
                    StreakCard("Best streak", p.bestStreak, Modifier.weight(1f))
                }
                HncCard(padding = 20.dp) {
                    Text("Last 14 days", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Muted("Cards rated, practice answers checked and mock questions answered each day.")
                    ActivityChart(p.days)
                }
                Eyebrow("By unit", Modifier.padding(top = 4.dp).semantics { heading() })
                p.units.forEach { UnitCard(it) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StreakCard(label: String, days: Int, modifier: Modifier) {
    HncCard(modifier.semantics(mergeDescendants = true) {}, padding = 18.dp) {
        Eyebrow(label)
        Text(
            if (days == 1) "1 day" else "$days days",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (label.startsWith("Current")) Muted(streakLabel(days) ?: "Rate a card to start one")
    }
}

/** Bar per day; each bar is labelled for TalkBack with the day and its count. */
@Composable
private fun ActivityChart(days: List<Pair<LocalDate, Int>>) {
    val extra = LocalHncColors.current
    val max = days.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val chartHeight = 120.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { (day, count) ->
            val label = "${formatWeekday(day)}: $count ${if (count == 1) "action" else "actions"}"
            Column(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (count > 0) count.toString() else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.muted,
                    maxLines = 1,
                )
                Box(Modifier.height(chartHeight).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (count == 0) 2.dp else chartHeight * (count.toFloat() / max))
                            .background(if (count == 0) extra.progressTrack else extra.bar, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                }
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.UK),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun UnitCard(u: UnitProgress) {
    HncCard(padding = 18.dp) {
        Text("${u.unit.code} · ${u.unit.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        ProgressBar(if (u.cards > 0) u.confident.toFloat() / u.cards else 0f)
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Confident ${u.confident} / ${u.cards} cards", style = MaterialTheme.typography.bodyMedium)
            Text("${u.due} due", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (u.quizAnswered > 0) "Quiz: ${u.quizCorrect} / ${u.quizAnswered} correct (${percent(u.quizCorrect, u.quizAnswered)}%)"
                else "Quiz: not started",
                style = MaterialTheme.typography.bodyMedium,
            )
            u.latestMock?.let { Text("Latest mock: ${it.correct} / ${it.asked} (${it.percent}%)", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
