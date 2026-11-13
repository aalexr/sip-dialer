package app.sipdialer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sipdialer.R
import app.sipdialer.SipDialerApp
import app.sipdialer.sip.RegistrationStatus
import app.sipdialer.ui.call.InCallActivity
import app.sipdialer.ui.contacts.ContactsScreen
import app.sipdialer.ui.dialer.DialerScreen
import app.sipdialer.ui.recents.RecentsScreen
import app.sipdialer.ui.settings.AccountScreen
import app.sipdialer.ui.theme.SipDialerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var dialRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        dialRequest = intent.numberToDial()
        setContent {
            SipDialerTheme {
                MainScreen(
                    dialRequest = dialRequest,
                    onCallStarted = { startActivity(InCallActivity.intent(this)) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.numberToDial()?.let { dialRequest = it }
    }

    private fun Intent.numberToDial(): String? = when (data?.scheme) {
        "tel" -> data?.schemeSpecificPart
        "sip" -> data?.toString()
        else -> null
    }
}

private enum class Tab(val label: String, val icon: Int) {
    KEYPAD("Keypad", R.drawable.ic_dialpad),
    RECENTS("Recents", R.drawable.ic_history),
    CONTACTS("Contacts", R.drawable.ic_person),
}

@Composable
private fun MainScreen(dialRequest: String?, onCallStarted: () -> Unit) {
    val app = SipDialerApp.instance
    val registration by app.sip.registration.collectAsStateWithLifecycle()
    val account by app.sip.account.collectAsStateWithLifecycle()
    val recents by app.sip.recents.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    RequestCallPermissions()

    LaunchedEffect(dialRequest) { if (dialRequest != null) tab = Tab.KEYPAD.ordinal }

    val placeCall: (String) -> Unit = { destination ->
        app.sip.dial(destination)
            .onSuccess { onCallStarted() }
            .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "Call failed") } }
    }

    if (showAccount) {
        BackHandler { showAccount = false }
        AccountScreen(
            account = account,
            status = registration,
            onSave = { cfg -> app.sip.saveAccount(cfg) },
            onRemove = { app.sip.removeAccount() },
            onBack = { showAccount = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Tab.entries[tab].label) },
                actions = {
                    RegistrationChip(registration, onClick = { showAccount = true })
                    IconButton(onClick = { showAccount = true }) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "SIP account")
                    }
                },
            )
        },
        bottomBar = {
            ShortNavigationBar {
                Tab.entries.forEach { t ->
                    ShortNavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = Tab.entries[tab],
            transitionSpec = { fadeIn(enterSpec) togetherWith fadeOut(exitSpec) },
            modifier = Modifier.padding(padding),
            label = "tab",
        ) { current ->
            when (current) {
                Tab.KEYPAD -> DialerScreen(initialNumber = dialRequest, onCall = placeCall)
                Tab.RECENTS -> RecentsScreen(recents = recents, onCall = placeCall)
                Tab.CONTACTS -> ContactsScreen(onCall = placeCall)
            }
        }
    }
}

@Composable
private fun RegistrationChip(status: RegistrationStatus, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (label, dot) = when (status) {
        RegistrationStatus.Registered -> "Online" to Color(0xFF34A853)
        RegistrationStatus.InProgress -> "Connecting" to c.tertiary
        is RegistrationStatus.Failed -> "Error" to c.error
        RegistrationStatus.Unregistered -> "Offline" to c.outline
        RegistrationStatus.NoAccount -> "Set up" to c.outline
    }
    AssistChip(
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        },
        shape = CircleShape,
        colors = AssistChipDefaults.assistChipColors(containerColor = c.surfaceContainerHigh),
        border = null,
    )
}

@Composable
private fun RequestCallPermissions() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        launcher.launch(permissions.toTypedArray())
    }
}
