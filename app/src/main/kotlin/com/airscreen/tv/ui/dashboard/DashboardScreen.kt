package com.airscreen.tv.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.airscreen.tv.R
import com.airscreen.tv.domain.model.DiscoveryStatus
import com.airscreen.tv.domain.model.PinChallenge
import com.airscreen.tv.domain.model.SessionInfo
import kotlinx.coroutines.flow.StateFlow

@Composable
fun DashboardScreen(
    status: StateFlow<DiscoveryStatus>,
    sessions: StateFlow<List<SessionInfo>>,
    pinChallenges: StateFlow<List<PinChallenge>>
) {
    val current by status.collectAsStateWithLifecycle()
    val activeSessions by sessions.collectAsStateWithLifecycle()
    val challenges by pinChallenges.collectAsStateWithLifecycle()
    val activePin = challenges.firstOrNull()
    val networkLabel = current.networkName
        ?: current.ipAddress
        ?: stringResource(R.string.dashboard_waiting_network)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = Color(0xFFE6EDF3)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = current.deviceName.ifBlank { stringResource(R.string.app_name) },
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFF4FC3F7)
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (activePin != null) {
                Text(
                    text = stringResource(R.string.dashboard_pin, activePin.code.value),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color(0xFFFFD54F)
                )
                Text(
                    text = stringResource(R.string.dashboard_pin_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9FB0BE)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (activeSessions.isNotEmpty()) {
                val active = activeSessions.first()
                Text(
                    text = stringResource(R.string.dashboard_session, active.senderName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFE6EDF3)
                )
                Text(
                    text = active.metrics.codec + " " + active.metrics.width + "x" +
                        active.metrics.height + " " + active.metrics.fps.toInt() + " fps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9FB0BE)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = when {
                    !current.isServiceRunning -> stringResource(R.string.dashboard_starting)
                    current.ipAddress == null -> stringResource(R.string.dashboard_waiting_network)
                    else -> stringResource(R.string.dashboard_running)
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE6EDF3)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dashboard_network, networkLabel),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF9FB0BE)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                StatusBadge(
                    label = stringResource(R.string.dashboard_airplay),
                    ready = current.airplayReady
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, ready: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFE6EDF3)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                if (ready) R.string.dashboard_ready else R.string.dashboard_off
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (ready) Color(0xFF4CAF50) else Color(0xFF7A8A99)
        )
    }
}