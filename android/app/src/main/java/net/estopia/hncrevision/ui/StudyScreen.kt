package net.estopia.hncrevision.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.estopia.hncrevision.logic.CardMode
import net.estopia.hncrevision.logic.DeckView
import net.estopia.hncrevision.logic.Rating
import net.estopia.hncrevision.logic.RatingFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(vm: AppViewModel) {
    val s = vm.snapshot
    val mode = s.cardMode
    val deck = s.deck(mode)
    val scroll = rememberScrollState()

    // Stop reading aloud when leaving the screen.
    DisposableEffect(Unit) { onDispose { vm.stopReading(pause = false) } }
    LaunchedEffect(deck.current?.card?.id, mode) { scroll.animateScrollTo(0) }

    ScreenScaffold(
        title = mode.title,
        actions = {
            if (mode != CardMode.CARD) {
                IconButton(onClick = { vm.act { shuffle(mode) } }, enabled = deck.size >= 2) {
                    Icon(AppIcons.Shuffle, contentDescription = "Shuffle")
                }
            }
            if (deck.usesRounds) {
                IconButton(onClick = { vm.act { restart(mode) } }, enabled = deck.size > 0) {
                    Icon(AppIcons.Restart, contentDescription = "Restart round")
                }
            }
        },
    ) {
        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh(manual = true) }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                ContentWidth(Modifier.padding(vertical = 12.dp)) {
                    FilterBar(s.filters, s.stats, vm)
                    if (s.newCardCount > 0 && mode != CardMode.NEW) {
                        Banner(
                            text = "${s.newCardCount} new or updated ${if (s.newCardCount == 1) "card" else "cards"} from the website",
                            action = "Study them",
                            onAction = { vm.act { setCardMode(CardMode.NEW) } },
                            closeLabel = "Dismiss new cards",
                            onClose = { vm.act { dismissNewCards() } },
                        )
                    }
                    if (mode in CardMode.SEGMENTED) {
                        Segmented(
                            options = CardMode.SEGMENTED,
                            selected = mode,
                            label = { m -> if (m == CardMode.DUE && s.stats.due > 0) "Due ${s.stats.due}" else if (m == CardMode.RATED) "Rated" else m.title },
                            onSelect = { m -> vm.act { setCardMode(m) } },
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (mode == CardMode.NEW) "New or updated cards from the website · ignores filters" else "Opened from the home screen widget",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalHncColors.current.muted,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            PlainButton("Back to Study", { vm.act { setCardMode(CardMode.STUDY) } })
                        }
                    }
                    if (mode == CardMode.RATED) RatingFilterPicker(s.ratingFilter) { f -> vm.act { setRatingFilter(f) } }
                    if (deck.usesRounds) Tracker(deck.tracker)
                    Flashcard(deck, vm)
                    DeckNavigation(deck, vm)
                    if (deck.current != null) {
                        ProgressBar(deck.progressFraction, Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Muted(deck.position)
                            Muted(deck.progressLabel)
                        }
                    }
                    Muted("Tip: answer aloud before revealing. Swipe the card left or right to move between cards.")
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RatingFilterPicker(filter: RatingFilter, onChange: (RatingFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = filter.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Flashcard rating") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RatingFilter.entries.forEach { f ->
                DropdownMenuItem(text = { Text(f.label) }, onClick = { onChange(f); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Flashcard(deck: DeckView, vm: AppViewModel) {
    val view = deck.current
    val mode = deck.mode
    if (view == null) {
        val toStudy = { vm.act { setCardMode(CardMode.STUDY) } }
        when (mode) {
            CardMode.DUE -> EmptyState(
                eyebrow = "Spaced repetition",
                title = "Nothing due. Nice work.",
                body = deck.nextDue?.let { "Next due: ${formatWeekday(it)}" }
                    ?: "Rate cards in Study and they’ll come back here when they’re due.",
                action = "Study cards" to toStudy,
            )
            CardMode.STARRED -> EmptyState(
                eyebrow = "Starred",
                title = "No starred cards yet",
                body = "Tap ☆ on any card to save it here.",
                action = "Study cards" to toStudy,
            )
            CardMode.NEW, CardMode.CARD -> EmptyState(
                eyebrow = "From the website",
                title = "You’re up to date",
                body = "You’ve seen every new or updated card.",
                action = "Back to Study" to toStudy,
            )
            else -> EmptyState(
                eyebrow = "Your revision set",
                title = if (mode == CardMode.RATED) "No cards match this rating" else "No cards in this set",
                body = "Choose another filter, or mark cards “Revisit” while studying.",
                action = "Start studying" to toStudy,
            )
        }
        return
    }
    val card = view.card
    var hintShown by rememberSaveable(card.id, deck.revealed) { mutableStateOf(false) }

    // Read the question when a card appears and the answer when it's revealed.
    LaunchedEffect(card.id, deck.revealed, vm.readAloud) {
        vm.autoRead(if (deck.revealed) card.a else card.q)
    }

    val offset = remember(card.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val next = { vm.act { move(mode, 1) } }
    val previous = { vm.act { move(mode, -1) } }

    HncCard(
        Modifier
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (kotlin.math.abs(offset.value) / (size.width.coerceAtLeast(1f) * 1.5f)).coerceIn(0f, 0.6f)
            }
            .pointerInput(card.id, mode, deck.index, deck.isLast) {
                val threshold = size.width * 0.22f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = offset.value
                        when {
                            v < -threshold -> next()
                            v > threshold && deck.index > 0 -> previous()
                        }
                        scope.launch { offset.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, drag ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + drag) }
                }
            }
            .then(if (!deck.revealed) Modifier.clickable(onClickLabel = "Reveal answer") { vm.act { reveal(mode) } } else Modifier)
            .semantics {
                customActions = listOfNotNull(
                    CustomAccessibilityAction(if (!deck.isLast) "Next card" else if (deck.usesRounds) "Next round" else "Start over") { next(); true },
                    if (deck.index > 0) CustomAccessibilityAction("Previous card") { previous(); true } else null,
                )
            },
        padding = 22.dp,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f).padding(top = 12.dp)) { MetaRow(view.tag, "${view.status} · #${card.id}", view.outcomes) }
            StarButton(view.starred, { vm.act { toggleStar(card.id) } })
        }
        Text(
            card.q,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 10.dp).semantics { heading() },
        )
        if (!deck.revealed) {
            if (hintShown) {
                HorizontalDivider(color = LocalHncColors.current.line)
                Text(
                    card.hint.ifBlank { "No hint for this card." },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(if (hintShown) "Hide hint" else "Show hint", {
                    hintShown = !hintShown
                    if (hintShown) vm.autoRead(card.hint) else vm.stopReading(pause = false)
                })
                PrimaryButton("Reveal answer", { vm.act { reveal(mode) } })
            }
        } else {
            HorizontalDivider(color = LocalHncColors.current.line)
            Text(
                card.a,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            ReferencesList(view.references)
            NoteSection(view.note) { text -> vm.act { setNote(card.id, text) } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlainButton("Revisit", { vm.act { rate(mode, Rating.REVIEW) } }, Modifier.weight(1f))
                SoftButton("Confident ✓", { vm.act { rate(mode, Rating.KNOWN) } }, Modifier.weight(1f))
            }
        }
        ReadAloudRow(vm, if (deck.revealed) card.a else if (hintShown) card.hint else card.q)
    }
}

@Composable
private fun DeckNavigation(deck: DeckView, vm: AppViewModel) {
    if (deck.current == null) return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainButton("← Previous", { vm.act { move(deck.mode, -1) } }, Modifier.weight(1f), enabled = deck.index > 0)
        PlainButton(
            when {
                !deck.isLast -> "Next →"
                deck.mode == CardMode.CARD -> "Back to Study →"
                deck.usesRounds -> "Next round →"
                else -> "Start over →"
            },
            { vm.act { move(deck.mode, 1) } },
            Modifier.weight(1f),
        )
    }
}
