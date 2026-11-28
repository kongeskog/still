package com.kongeskog.still.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
fun WelcomeScreen(onLink: () -> Unit) {
    Screen("Still") {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextMMD(stringResource(R.string.signal_messages_on_your_kompakt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextMMD(
                stringResource(R.string.still_joins_your_signal_account_as_a_lin),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextMMD(stringResource(R.string.no_signal_account_yet_create_one_in_the_), style = MaterialTheme.typography.bodyLarge)
            TextMMD(stringResource(R.string.still_is_not_made_by_signal_or_mudita), style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.padding(16.dp)) {
            PrimaryButton(stringResource(R.string.link_to_signal), Modifier.fillMaxWidth(), onClick = onLink)
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
    Screen(stringResource(R.string.link_to_signal), onBack) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                LinkUi.Connecting -> Note(stringResource(R.string.connecting_to_signal))
                LinkUi.Registering -> Note(stringResource(R.string.linked_setting_up))
                is LinkUi.Failed -> {
                    Note(stringResource(R.string.linking_did_not_finish))
                    TextMMD(state.reason, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
                }
                is LinkUi.Code -> {
                    QrCode(state.url, Modifier.size(200.dp))
                    Step("1", stringResource(R.string.in_signal_settings_linked_devices_link_a))
                    Step("2", stringResource(R.string.scan_this_code_signal_on_this_phone_phot))
                    Step("3", stringResource(R.string.choose_transfer_message_history))
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (state) {
                is LinkUi.Failed -> PrimaryButton(stringResource(R.string.try_again), Modifier.fillMaxWidth(), onClick = onRetry)
                // A code lasts 90 seconds; a fresh one gives the full time for the photo.
                is LinkUi.Code -> SecondaryButton(stringResource(R.string.new_code), Modifier.fillMaxWidth(), onClick = onRetry)
                else -> Spacer(Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(Modifier.fillMaxWidth()) {
        TextMMD(number, Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        TextMMD(text, style = MaterialTheme.typography.bodyMedium)
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
