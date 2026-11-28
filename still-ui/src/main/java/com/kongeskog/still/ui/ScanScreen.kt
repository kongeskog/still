package com.kongeskog.still.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD

/** The camera, reading one code, in Still's own black and white. */
@Composable
fun ScanQrScreen(onBack: () -> Unit, onRead: (String) -> Unit, onUnavailable: () -> Unit) {
    Screen(stringResource(R.string.scan_link_code), onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            TextMMD(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyMedium)
            QrScanner(
                starting = stringResource(R.string.scan_starting),
                onRead = { text ->
                    onRead(text)
                    true
                },
                onUnavailable = onUnavailable,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
