package app.sipdialer.ui.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.sipdialer.R
import app.sipdialer.ui.components.Avatar
import app.sipdialer.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactNumber(val name: String, val number: String, val label: String, val isSip: Boolean)

@Composable
fun ContactsScreen(onCall: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    icon = R.drawable.ic_person,
                    title = "Your contacts",
                    body = "Allow access to call your contacts' phone numbers and SIP addresses.",
                    modifier = Modifier.weight(1f, fill = false),
                )
                Button(
                    onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.padding(bottom = 32.dp),
                ) { Text("Allow access") }
            }
        }
        return
    }

    var contacts by remember { mutableStateOf<List<ContactNumber>?>(null) }
    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { loadContacts(context) } }

    val list = contacts ?: return
    if (list.isEmpty()) {
        EmptyState(R.drawable.ic_person, "No contacts", "Contacts with a phone number or SIP address appear here.", modifier)
        return
    }

    // Group by initial: each letter gets its own block of segmented items.
    val groups = list.groupBy { it.name.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#' }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        groups.forEach { (letter, entries) ->
            item(key = "header-$letter") {
                Text(
                    letter.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(entries, key = { i, c -> "$letter-$i-${c.number}" }) { index, contact ->
                SegmentedListItem(
                    onClick = { onCall(contact.number) },
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = entries.size),
                    leadingContent = { Avatar(contact.name) },
                    supportingContent = { Text("${contact.label} · ${contact.number}") },
                ) { Text(contact.name) }
            }
        }
    }
}

private fun loadContacts(context: Context): List<ContactNumber> {
    val result = mutableListOf<ContactNumber>()
    val resolver = context.contentResolver

    resolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        ),
        null,
        null,
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0) ?: continue
            val number = c.getString(1) ?: continue
            val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3))
            result += ContactNumber(name, number, label.toString(), isSip = false)
        }
    }

    resolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS,
        ),
        "${ContactsContract.Data.MIMETYPE} = ?",
        arrayOf(ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE),
        null,
    )?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0) ?: continue
            val address = c.getString(1) ?: continue
            result += ContactNumber(name, address, "SIP", isSip = true)
        }
    }

    return result
        .distinctBy { it.name to it.number.filterNot(Char::isWhitespace) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
