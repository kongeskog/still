package com.kongeskog.still.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD

data class CheckLine(val name: String, val ok: Boolean, val detail: String)

/** Runs the library self-checks and lists the results. Worth a run after a library bump. */
@Composable
fun SpikeScreen(lines: List<CheckLine>, running: Boolean, onBack: () -> Unit, onRun: () -> Unit) {
    Screen("Library checks", onBack) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (lines.isEmpty() && !running) {
                TextMMD("The checks have not run yet.", style = MaterialTheme.typography.bodyLarge)
            }
            lines.forEach { line ->
                Column {
                    TextMMD(
                        (if (line.ok) "✓ " else "✗ ") + line.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TextMMD(line.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (running) TextMMD("Running…", style = MaterialTheme.typography.bodyLarge)
        }
        PrimaryButton("Run checks", Modifier.fillMaxWidth().padding(16.dp), enabled = !running, onClick = onRun)
    }
}
