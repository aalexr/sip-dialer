package app.sipdialer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.sipdialer.R
import app.sipdialer.sip.RegistrationStatus
import app.sipdialer.sip.SipAccountConfig
import app.sipdialer.sip.Transport

@Composable
fun AccountScreen(
    account: SipAccountConfig?,
    status: RegistrationStatus,
    onSave: (SipAccountConfig) -> Result<Unit>,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    var displayName by rememberSaveable { mutableStateOf(account?.displayName.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(account?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(account?.password.orEmpty()) }
    var domain by rememberSaveable { mutableStateOf(account?.domain.orEmpty()) }
    var proxy by rememberSaveable { mutableStateOf(account?.proxy.orEmpty()) }
    var authUsername by rememberSaveable { mutableStateOf(account?.authUsername.orEmpty()) }
    var transport by rememberSaveable { mutableStateOf(account?.transport ?: Transport.TLS) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(proxy.isNotEmpty() || authUsername.isNotEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val canSave = username.isNotBlank() && domain.isNotBlank()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("SIP account") },
                subtitle = { Text(account?.let { "${it.username}@${it.domain}" } ?: "Not set up") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (account != null) StatusCard(status)

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.trim() },
                label = { Text("Username / extension") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it.trim() },
                label = { Text("Domain") },
                placeholder = { Text("sip.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Transport", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            TransportSelector(transport, onSelect = { transport = it })

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced")
            }
            AnimatedVisibility(showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = proxy,
                        onValueChange = { proxy = it.trim() },
                        label = { Text("Proxy / registrar (host:port)") },
                        supportingText = { Text("Leave empty to use the domain") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = authUsername,
                        onValueChange = { authUsername = it.trim() },
                        label = { Text("Auth username") },
                        supportingText = { Text("Only if it differs from the username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                if (account != null) {
                    OutlinedButton(
                        onClick = onRemove,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Remove") }
                }
                Button(
                    onClick = {
                        error = onSave(
                            SipAccountConfig(
                                username = username,
                                password = password,
                                domain = domain,
                                transport = transport,
                                displayName = displayName.trim(),
                                proxy = proxy,
                                authUsername = authUsername,
                            ),
                        ).exceptionOrNull()?.message
                    },
                    enabled = canSave,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text(if (account == null) "Sign in" else "Save") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Expressive "connected" button group: the selected segment morphs to a rounder shape. */
@Composable
private fun TransportSelector(selected: Transport, onSelect: (Transport) -> Unit) {
    val options = Transport.entries
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) { Text(option.name) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(status: RegistrationStatus) {
    val c = MaterialTheme.colorScheme
    val (container, content, text) = when (status) {
        RegistrationStatus.Registered -> Triple(c.primaryContainer, c.onPrimaryContainer, "Registered, ready for calls")
        RegistrationStatus.InProgress -> Triple(c.secondaryContainer, c.onSecondaryContainer, "Registering…")
        is RegistrationStatus.Failed -> Triple(c.errorContainer, c.onErrorContainer, "Failed: ${status.reason}")
        RegistrationStatus.Unregistered -> Triple(c.surfaceContainerHigh, c.onSurface, "Not registered")
        RegistrationStatus.NoAccount -> Triple(c.surfaceContainerHigh, c.onSurface, "No account")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            if (status == RegistrationStatus.InProgress) {
                LoadingIndicator(modifier = Modifier.size(32.dp), color = content)
            } else {
                Icon(painterResource(R.drawable.ic_call), contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.size(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
