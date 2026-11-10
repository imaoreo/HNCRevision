package net.estopia.hncrevision.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.estopia.hncrevision.data.VlsmExercise
import net.estopia.hncrevision.logic.VlsmChecker
import net.estopia.hncrevision.logic.VlsmDifficulty

private val AddressKeyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next)

@Composable
private fun cardFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = LocalHncColors.current.card,
    focusedContainerColor = LocalHncColors.current.card,
)

// region Generate

/** VLSM → Generate: random questions by difficulty, with answer boxes that can be checked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VlsmGenerate(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.ensureGenerated() }
    val exercise = vm.generated ?: return
    val key = exercise.hashCode()
    var hintsShown by rememberSaveable(key) { mutableIntStateOf(0) }
    var showAnswer by rememberSaveable(key) { mutableStateOf(false) }

    HncCard(padding = 22.dp) {
        Eyebrow("Generated question")
        Text("Practise with a new network", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Segmented(VlsmDifficulty.entries, vm.vlsmDifficulty, { it.label }, { vm.newGenerated(it) })
        Muted(
            when (vm.vlsmDifficulty) {
                VlsmDifficulty.EASY -> "A /24 with 3 LANs."
                VlsmDifficulty.MEDIUM -> "A /24 with 4–5 LANs."
                VlsmDifficulty.HARD -> "A /23 or /22 with 5–6 LANs."
            },
        )
        Text("Allocate subnets within ${exercise.parent}.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            exercise.requests.forEach { Text("• ${it.lan} needs ${it.hosts} usable addresses", style = MaterialTheme.typography.bodyLarge) }
        }
        Text(
            "Find each LAN’s network address and prefix, subnet mask, usable host range and broadcast address.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = vm.generatedWorking,
            onValueChange = { vm.generatedWorking = it },
            label = { Text("Your working") },
            supportingText = { Text("Not saved for generated questions.") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            shape = ControlShape,
            colors = cardFieldColors(),
        )
        if (hintsShown > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                exercise.hints.take(hintsShown).forEachIndexed { i, h -> Text("${i + 1}. $h", style = MaterialTheme.typography.bodyMedium) }
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
            SoftButton("New question", { vm.newGenerated() })
        }
    }
    AnswerBoxes(exercise, vm)
    if (showAnswer) Solution(exercise)
}

@Composable
private fun AnswerBoxes(exercise: VlsmExercise, vm: AppViewModel) {
    val extra = LocalHncColors.current
    val checked = vm.generatedChecked
    HncCard(padding = 22.dp) {
        Text("Your answers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Muted("Largest LAN first. Write the network with its prefix, like 192.168.1.0/26.")
        exercise.rows.forEachIndexed { r, row ->
            val answers = vm.generatedAnswers.getOrNull(r) ?: return@forEachIndexed
            val marks = if (checked) VlsmChecker.check(row, answers) else null
            Text(
                "${r + 1}. ${row.lan} · ${row.hosts} hosts",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp).semantics { heading() },
            )
            VlsmChecker.FIELDS.forEachIndexed { f, label ->
                val ok = marks?.get(f)
                OutlinedTextField(
                    value = answers[f],
                    onValueChange = { vm.setGeneratedAnswer(r, f, it) },
                    label = { Text(label) },
                    singleLine = true,
                    isError = ok == false,
                    trailingIcon = when (ok) {
                        true -> { { Icon(AppIcons.Check, contentDescription = null, tint = extra.correctBorder) } }
                        false -> { { Icon(AppIcons.Close, contentDescription = null, tint = extra.wrongBorder) } }
                        null -> null
                    },
                    supportingText = when (ok) {
                        true -> { { Text("Correct") } }
                        false -> { { Text("Not right yet") } }
                        null -> null
                    },
                    keyboardOptions = AddressKeyboard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { if (ok != null) stateDescription = if (ok) "Correct" else "Incorrect" },
                    shape = ControlShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = extra.card,
                        focusedContainerColor = extra.card,
                        unfocusedBorderColor = if (ok == true) extra.correctBorder else extra.line,
                        focusedBorderColor = if (ok == true) extra.correctBorder else MaterialTheme.colorScheme.onSurface,
                        unfocusedSupportingTextColor = if (ok == true) extra.correctBorder else extra.muted,
                        focusedSupportingTextColor = if (ok == true) extra.correctBorder else extra.muted,
                    ),
                )
            }
        }
        PrimaryButton("Check answers", { vm.checkGenerated() }, Modifier.fillMaxWidth())
        if (checked) {
            val (right, total) = VlsmChecker.score(exercise.rows, vm.generatedAnswers.toList())
            Text(
                "$right of $total correct",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// endregion

// region Drills

/** VLSM → Drills: rounds of 10 quick questions; the best score is saved. */
@Composable
fun VlsmDrill(vm: AppViewModel) {
    val extra = LocalHncColors.current
    HncCard(padding = 22.dp) {
        Eyebrow("Subnetting drills")
        if (vm.drillDone) {
            Text(
                "${vm.drillScore} / ${vm.drillRound.size}",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
            Muted("Best score: ${vm.drillBest} / ${vm.drillRound.size}")
            PrimaryButton("New round", { vm.newDrillRound() }, Modifier.fillMaxWidth())
            return@HncCard
        }
        val q = vm.drillQuestion ?: return@HncCard
        val result = vm.drillResults.getOrNull(vm.drillIndex)
        Muted("Question ${vm.drillIndex + 1} of ${vm.drillRound.size} · ${vm.drillScore} correct · Best ${vm.drillBest} / ${vm.drillRound.size}")
        Text(q.prompt, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = vm.drillInput,
            onValueChange = { if (result == null) vm.drillInput = it },
            label = { Text("Your answer") },
            singleLine = true,
            readOnly = result != null,
            keyboardOptions = AddressKeyboard.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (result == null) vm.checkDrill() else vm.nextDrill() }),
            modifier = Modifier.fillMaxWidth(),
            shape = ControlShape,
            colors = cardFieldColors(),
        )
        if (result == null) {
            PrimaryButton("Check", { vm.checkDrill() }, Modifier.fillMaxWidth(), enabled = vm.drillInput.isNotBlank())
        } else {
            Surface(
                shape = ControlShape,
                color = if (result) extra.correctBackground else extra.wrongBackground,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (result) "Correct" else "Not quite", style = MaterialTheme.typography.titleMedium)
                    Text("Answer: ${q.answer}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(q.working, style = MaterialTheme.typography.bodyMedium)
                }
            }
            PrimaryButton(
                if (vm.drillIndex == vm.drillRound.size - 1) "See score" else "Next question →",
                { vm.nextDrill() },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

// endregion
