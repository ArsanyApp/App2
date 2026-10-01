package com.smsextractor.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smsextractor.app.SenderInfo
import com.smsextractor.core.SmsParser
import com.smsextractor.core.TextNormalizer
import com.smsextractor.core.Transaction
import com.smsextractor.core.TxType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SenderDialog(
    senders: List<SenderInfo>,
    selected: Set<String>?,
    loading: Boolean,
    onDone: (Set<String>?) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf(selected) } // null = all
    var search by remember { mutableStateOf("") }
    val shown = senders.filter { search.isBlank() || it.address.contains(search.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select SMS Sender") },
        text = {
            Column {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Search sender") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().clickable { current = if (current == null) emptySet() else null },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = current == null, onCheckedChange = { current = if (it) null else emptySet() })
                    Text("All Senders", fontWeight = FontWeight.Bold)
                }
                HorizontalDivider()
                if (senders.isEmpty()) {
                    Text(
                        if (loading) "Reading senders from your SMS inbox…" else "No senders found yet.",
                        modifier = Modifier.padding(8.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.address }) { s ->
                        val checked = current == null || s.address in current!!
                        fun toggle() {
                            val base = current ?: senders.map { it.address }.toSet()
                            current = if (checked) base - s.address else base + s.address
                        }
                        Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Column {
                                Text(s.address)
                                val info = buildList {
                                    s.smsCount?.let { add("$it SMS") }
                                    if (s.importedCount > 0) add("${s.importedCount} imported")
                                }.joinToString(" · ")
                                if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(current) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PasteDialog(onImport: (String, String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste SMS manually") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Copy the bank messages and paste them here. Leave an empty line between messages.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = label, onValueChange = { label = it }, singleLine = true,
                    label = { Text("Sender name (e.g. NBE)") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Messages") }, minLines = 6, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text, label) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EditDialog(t: Transaction, onSave: (Transaction) -> Unit, onDelete: (Transaction) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(t.date?.toString() ?: "") }
    var time by remember { mutableStateOf(t.time ?: "") }
    var action by remember { mutableStateOf(t.action ?: "") }
    var type by remember { mutableStateOf(t.type) }
    var amount by remember { mutableStateOf(t.amount?.toPlainString() ?: "") }
    var currency by remember { mutableStateOf(t.currency ?: "") }
    var from by remember { mutableStateOf(t.fromParty ?: "") }
    var to by remember { mutableStateOf(t.toParty ?: "") }
    var merchant by remember { mutableStateOf(t.merchant ?: "") }
    var suffix by remember { mutableStateOf(t.accountSuffix ?: "") }
    var reference by remember { mutableStateOf(t.reference ?: "") }
    var balance by remember { mutableStateOf(t.balance?.toPlainString() ?: "") }
    var reviewed by remember { mutableStateOf(!t.needsReview) }
    var error by remember { mutableStateOf<String?>(null) }
    var typeMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val suggestion: LocalDate? = remember(t) {
        if (t.date == null && t.partialMonth != null && t.partialDay != null)
            SmsParser.suggestDate(t.partialMonth!!, t.partialDay!!, t.smsTimestamp) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Original SMS (from ${t.sender}):", fontWeight = FontWeight.Bold)
                Text(t.originalText, style = MaterialTheme.typography.bodyMedium)
                t.smsTimestamp?.let {
                    val received = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                    Text("Received: ${received.toLocalDate()} ${received.toLocalTime().withNano(0)}", style = MaterialTheme.typography.bodySmall)
                }
                if (t.reviewNotes.isNotBlank()) {
                    Text("Needs review: ${t.reviewNotes}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()

                Field("Date (yyyy-mm-dd)", date) { date = it }
                if (t.date == null && t.dateText != null) {
                    Text("Date written in SMS: ${t.dateText}", style = MaterialTheme.typography.bodySmall)
                }
                if (suggestion != null && date.isBlank()) {
                    OutlinedButton(onClick = { date = suggestion.toString() }) {
                        Text("Use $suggestion (year from SMS received date)")
                    }
                }
                Field("Time (hh:mm)", time) { time = it }
                Field("Action (as written in SMS)", action) { action = it }
                Text("Transaction type", style = MaterialTheme.typography.labelSmall)
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(type.label) }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        for (tt in TxType.entries) {
                            DropdownMenuItem(text = { Text(tt.label) }, onClick = { type = tt; typeMenu = false })
                        }
                    }
                }
                Field("Amount", amount) { amount = it }
                Field("Currency (e.g. EGP)", currency) { currency = it }
                Field("Sender/person", from) { from = it }
                Field("Recipient/person", to) { to = it }
                Field("Merchant", merchant) { merchant = it }
                Field("Account/card suffix", suffix) { suffix = it }
                Field("Reference number", reference) { reference = it }
                Field("Available balance", balance) { balance = it }
                Row(Modifier.clickable { reviewed = !reviewed }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = reviewed, onCheckedChange = { reviewed = it })
                    Text("I checked this – details are correct")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete this transaction", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedDate = if (date.isBlank()) null else runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                val parsedAmount = if (amount.isBlank()) null else TextNormalizer.cleanNumber(amount)?.let { BigDecimal(it) }
                val parsedBalance = if (balance.isBlank()) null else TextNormalizer.cleanNumber(balance)?.let { BigDecimal(it) }
                val cleanTime = time.trim()
                error = when {
                    date.isNotBlank() && parsedDate == null -> "Date must look like 2026-09-24"
                    amount.isNotBlank() && parsedAmount == null -> "Amount must be a number, e.g. 1500.00"
                    balance.isNotBlank() && parsedBalance == null -> "Balance must be a number"
                    cleanTime.isNotEmpty() && !Regex("""([01]?\d|2[0-3]):[0-5]\d""").matches(cleanTime) -> "Time must look like 22:08"
                    else -> null
                }
                if (error == null) {
                    onSave(
                        t.copy(
                            date = parsedDate,
                            time = cleanTime.ifEmpty { null },
                            action = action.trim().ifEmpty { null },
                            type = type,
                            amount = parsedAmount,
                            currency = currency.trim().uppercase().ifEmpty { null },
                            fromParty = from.trim().ifEmpty { null },
                            toParty = to.trim().ifEmpty { null },
                            merchant = merchant.trim().ifEmpty { null },
                            accountSuffix = suffix.trim().ifEmpty { null },
                            reference = reference.trim().ifEmpty { null },
                            balance = parsedBalance,
                            balanceCurrency = if (parsedBalance == null) null else (t.balanceCurrency ?: currency.trim().uppercase().ifEmpty { null }),
                            needsReview = !reviewed,
                            reviewNotes = if (reviewed) "" else t.reviewNotes.split("; ").filter {
                                // a date typed by the user resolves the date-related notes
                                parsedDate == null || !(it.startsWith("Year") || it.startsWith("Transaction date") || it.startsWith("Day/month"))
                            }.joinToString("; "),
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transaction?") },
            text = { Text("It will be removed from this app. Importing the same SMS again will add it back.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(t) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}
