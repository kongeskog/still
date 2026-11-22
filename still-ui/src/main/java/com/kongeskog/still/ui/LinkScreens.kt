package com.kongeskog.still.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mudita.mmd.components.text.TextMMD

/** First run: nothing is linked yet. */
@Composable
fun WelcomeScreen(onLink: () -> Unit, onChecks: () -> Unit) {
    Screen("Still") {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextMMD("Link this phone to your Signal account.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextMMD(
                "Signal stays installed and stays your main app. Still is added as a linked device, like Signal Desktop.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton("Library checks", Modifier.fillMaxWidth(), onClick = onChecks)
            PrimaryButton("Link to Signal", Modifier.fillMaxWidth(), onClick = onLink)
        }
    }
}

sealed interface LinkUi {
    data object Connecting : LinkUi
    data class Code(val url: String) : LinkUi
    data object Registering : LinkUi
    data class Failed(val reason: String) : LinkUi
}

@Composable
fun LinkScreen(state: LinkUi, onBack: () -> Unit, onRetry: () -> Unit) {
    Screen("Link to Signal", onBack) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                LinkUi.Connecting -> Note("Connecting to Signal…")
                LinkUi.Registering -> Note("Signal approved the link. Setting up this device…")
                is LinkUi.Failed -> {
                    Note("Linking did not finish.")
                    TextMMD(state.reason, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                }
                is LinkUi.Code -> {
                    QrCode(state.url, Modifier.size(264.dp))
                    TextMMD(
                        "In Signal: Settings, Linked devices, Link a new device, then scan this code. " +
                            "It is replaced every 90 seconds, and keeps working after you leave this screen.\n" +
                            "Signal on this same phone cannot scan its own screen; see the README.",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is LinkUi.Failed -> PrimaryButton("Try again", Modifier.fillMaxWidth(), onClick = onRetry)
                else -> Spacer(Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    TextMMD(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
}

/** Pure black and white, with whole-pixel modules so the e-ink panel shows no grey edges. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier) {
        val cell = (size.minDimension / matrix.width).toInt().coerceAtLeast(1).toFloat()
        val origin = ((size.minDimension - cell * matrix.width) / 2).toInt().toFloat()
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
                }
            }
        }
    }
}
