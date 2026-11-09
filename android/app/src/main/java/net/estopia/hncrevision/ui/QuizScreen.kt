package net.estopia.hncrevision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.estopia.hncrevision.logic.QuizQuestionView
import net.estopia.hncrevision.logic.QuizResults
import net.estopia.hncrevision.logic.QuizView
import net.estopia.hncrevision.logic.ResultItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(vm: AppViewModel) {
    val s = vm.snapshot
    val quiz = s.quiz
    val scroll = rememberScrollState()
    LaunchedEffect(quiz.index, quiz.results != null, quiz.size) { scroll.animateScrollTo(0) }

    ScreenScaffold(title = "Quiz") {
        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh(manual = true) }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                ContentWidth(Modifier.padding(vertical = 12.dp)) {
                    FilterBar(s.filters, s.stats, vm)
                    Tracker(quiz.tracker)
                    when {
                        quiz.isEmpty -> EmptyState(
                            eyebrow = null,
                            title = "No MCQs for this filter",
                            body = "Choose another outcome or topic. Graded Unit sections 2 and 3 require written responses; use the Outcomes tab for practice tasks.",
                        )
                        quiz.results != null -> Results(quiz.results, vm)
                        else -> QuestionPanel(quiz, vm)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionPanel(quiz: QuizView, vm: AppViewModel) {
    val q = quiz.current ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Muted("Question ${quiz.index + 1} of ${quiz.size} · ${quiz.correctSoFar}/${quiz.answeredCount} correct so far")
        Muted("Saved latest answers: ${quiz.savedCorrect}/${quiz.savedTotal} correct")
    }
    HncCard(padding = 22.dp) {
        MetaRow(q.tag, q.meta)
        Text(q.question.q, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            q.options.forEach { option -> OptionRow(q, option.original, "${option.letter}.", option.text) { vm.act { choose(option.original) } } }
        }
        if (!q.checked) {
            PrimaryButton("Check answer", { vm.act { check() } }, enabled = q.choice != null)
        } else {
            Feedback(q)
        }
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PlainButton("← Previous", { vm.act { quizPrevious() } }, enabled = quiz.index > 0)
        PlainButton("Finish & review", { vm.act { finishQuiz() } })
        PrimaryButton(if (quiz.isLast) "See results" else "Next question →", { vm.act { quizNext() } }, enabled = q.checked)
    }
    Muted(
        "Choose one answer, then check it. Your first checked answer is scored for this attempt. " +
            "Quiz results are saved on this device separately from flashcard ratings.",
    )
}

@Composable
private fun OptionRow(q: QuizQuestionView, original: Int, letter: String, text: String, onSelect: () -> Unit) {
    val extra = LocalHncColors.current
    val selected = q.choice == original
    val isAnswer = original == q.question.answer
    val (background, border) = when {
        q.checked && isAnswer -> extra.correctBackground to extra.correctBorder
        q.checked && selected -> extra.wrongBackground to extra.wrongBorder
        selected -> extra.selectedOption to MaterialTheme.colorScheme.onSurface
        else -> extra.card to extra.line
    }
    val state = when {
        q.checked && isAnswer && selected -> "Your answer, correct"
        q.checked && isAnswer -> "Correct answer"
        q.checked && selected -> "Your answer, incorrect"
        else -> null
    }
    Surface(
        shape = ControlShape,
        color = background,
        border = BorderStroke(if (selected || (q.checked && isAnswer)) 1.5.dp else 1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = !q.checked, role = Role.RadioButton, onClick = onSelect)
            .semantics { if (state != null) stateDescription = state },
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = !q.checked,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.onSurface,
                    disabledSelectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                buildString { append(letter); append(' '); append(text) },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (q.checked && isAnswer) Icon(AppIcons.Check, contentDescription = null, tint = extra.correctBorder)
        }
    }
}

@Composable
private fun Feedback(q: QuizQuestionView) {
    val extra = LocalHncColors.current
    Surface(
        shape = ControlShape,
        color = if (q.isCorrect) extra.correctBackground else extra.wrongBackground,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (q.isCorrect) "Correct" else "Review this one", style = MaterialTheme.typography.titleMedium)
            Text("Correct answer: ${q.question.options[q.question.answer]}", style = MaterialTheme.typography.bodyLarge)
            if (q.question.explanation.isNotBlank()) Text(q.question.explanation, style = MaterialTheme.typography.bodyLarge)
            ReferencesList(q.references)
            Muted("Related flashcard: ${q.question.cardId}. This answer supports revision; it does not demonstrate practical competence.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Results(r: QuizResults, vm: AppViewModel) {
    HncCard(padding = 22.dp) {
        Eyebrow("Practice results")
        Text(
            "${r.correct} correct out of ${r.answered} answered",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "${r.wrong} to revise · ${r.unanswered} unanswered · ${r.total} questions in this set",
            style = MaterialTheme.typography.bodyLarge,
            color = LocalHncColors.current.muted,
        )
        Muted("This is a revision score, not an official unit grade. Flashcard confidence ratings are separate.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlainButton("Retry incorrect (${r.wrong})", { vm.act { retryIncorrect() } }, enabled = r.wrong > 0)
            PrimaryButton("Next round", { vm.act { quizNextRound() } })
            if (r.unanswered > 0) PlainButton("Continue unanswered", { vm.act { continueUnanswered() } })
        }
    }
    r.items.forEach { ResultRow(it) }
}

@Composable
private fun ResultRow(item: ResultItem) {
    var open by rememberSaveable(item.question.id) { mutableStateOf(false) }
    val extra = LocalHncColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = extra.card,
        border = BorderStroke(1.dp, extra.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
                    .heightIn(min = 48.dp)
                    .padding(14.dp)
                    .semantics(mergeDescendants = true) {
                        stateDescription = (if (item.correct) "Correct" else "To revise") + if (open) ", expanded" else ", collapsed"
                    },
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    if (item.correct) "✓" else "↺",
                    fontWeight = FontWeight.Bold,
                    color = if (item.correct) extra.correctBorder else extra.wrongBorder,
                    modifier = Modifier.width(24.dp),
                )
                Text(item.question.q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your answer: ${item.yourAnswer}", style = MaterialTheme.typography.bodyMedium)
                    Text("Correct answer: ${item.correctAnswer}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (item.question.explanation.isNotBlank()) Text(item.question.explanation, style = MaterialTheme.typography.bodyMedium)
                    ReferencesList(item.references)
                    Muted(item.footer)
                }
            }
        }
    }
}
