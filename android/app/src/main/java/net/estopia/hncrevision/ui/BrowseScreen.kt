package net.estopia.hncrevision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.estopia.hncrevision.logic.CardView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(vm: AppViewModel) {
    val s = vm.snapshot
    // Like the site, changing unit clears the search.
    var query by rememberSaveable(s.filters.unit) { mutableStateOf("") }
    val needle = query.trim().lowercase()
    val rows = s.selected.filter { v ->
        needle.isEmpty() || "${v.card.q} ${v.card.a} ${v.card.topic} ${v.card.unit} ${v.note.orEmpty()}".lowercase().contains(needle)
    }

    DisposableEffect(Unit) { onDispose { vm.stopReading(pause = false) } }

    ScreenScaffold(title = "Browse") {
        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh(manual = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "filters") { Centered { FilterBar(s.filters, s.stats, vm) } }
                item(key = "search") {
                    Centered {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search questions, answers or notes…") },
                            label = { Text("Search flashcards") },
                            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                { IconButton(onClick = { query = "" }) { Icon(AppIcons.Close, contentDescription = "Clear search") } }
                            } else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            shape = ControlShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = LocalHncColors.current.card,
                                focusedContainerColor = LocalHncColors.current.card,
                            ),
                        )
                    }
                }
                item(key = "count") { Centered { Muted("${rows.size} of ${s.selected.size} cards") } }
                if (rows.isEmpty()) {
                    item(key = "empty") { Centered { Muted("No matching cards. Try another filter or search.") } }
                }
                items(rows, key = { it.card.id }) { v -> Centered { BrowseRow(v, vm) } }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = 16.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseRow(v: CardView, vm: AppViewModel) {
    val card = v.card
    var open by rememberSaveable(card.id) { mutableStateOf(false) }
    var hint by rememberSaveable(card.id) { mutableStateOf(false) }
    val extra = LocalHncColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = extra.card,
        border = BorderStroke(1.dp, extra.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(onClickLabel = if (open) "Hide answer" else "Show answer") { open = !open }
                        .heightIn(min = 48.dp)
                        .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp)
                        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        card.q,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null)
                }
                StarButton(v.starred, { vm.act { toggleStar(card.id) } }, Modifier.padding(top = 4.dp, end = 4.dp))
            }
            AnimatedVisibility(open) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(card.a, style = MaterialTheme.typography.bodyLarge)
                    ReferencesList(v.references)
                    TextButton(onClick = { hint = !hint }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (hint) "Hide hint" else "Show hint")
                    }
                    if (hint) Text(card.hint.ifBlank { "No hint for this card." }, style = MaterialTheme.typography.bodyMedium)
                    NoteSection(v.note) { text -> vm.act { setNote(card.id, text) } }
                    Muted("${card.unit} · ${card.topic} · ${v.outcomes} · Card ${card.id}")
                    if (vm.readAloud) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconTextButton("Read question", AppIcons.Speaker) { vm.readNow(card.q) }
                            if (card.hint.isNotBlank()) IconTextButton("Read hint", AppIcons.Speaker) { vm.readNow(card.hint) }
                            IconTextButton("Read answer", AppIcons.Speaker) { vm.readNow(card.a) }
                            IconTextButton("Stop reading", AppIcons.Stop) { vm.stopReading() }
                        }
                    }
                }
            }
        }
    }
}
