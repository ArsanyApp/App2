package com.smsextractor.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smsextractor.app.MainViewModel
import com.smsextractor.app.UiState
import com.smsextractor.core.Totals
import com.smsextractor.core.Transaction
import com.smsextractor.core.TxType
import java.time.LocalDate

private enum class Pending { NONE, LOAD_SENDERS, IMPORT }

@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showSenderDialog by rememberSaveable { mutableStateOf(false) }
    var showPasteDialog by rememberSaveable { mutableStateOf(false) }
    var showUndated by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var pending by remember { mutableStateOf(Pending.NONE) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            when (pending) {
                Pending.LOAD_SENDERS -> { vm.loadSendersFromSms(); showSenderDialog = true }
                Pending.IMPORT -> vm.importSms()
                Pending.NONE -> {}
            }
        } else {
            vm.showMessage("SMS permission was denied. You can still use \"Paste SMS manually\".")
        }
        pending = Pending.NONE
    }

    fun withSmsPermission(action: Pending) {
        if (hasSmsPermission(context)) {
            when (action) {
                Pending.LOAD_SENDERS -> { vm.loadSendersFromSms(); showSenderDialog = true }
                Pending.IMPORT -> vm.importSms()
                Pending.NONE -> {}
            }
        } else {
            pending = action
            permissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            vm.clearMessage()
        }
    }

    val hScroll = rememberScrollState()

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("SMS Transactions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Everything stays on this phone. Nothing is sent or uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                    // 1. Sender selection
                    SenderSection(
                        state = state,
                        onChoose = { withSmsPermission(Pending.LOAD_SENDERS) },
                        onChooseWithoutSms = { showSenderDialog = true },
                        onPaste = { showPasteDialog = true },
                    )

                    // 2. Import
                    Button(onClick = { withSmsPermission(Pending.IMPORT) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Import SMS")
                    }

                    // 3. Dates
                    DateSection(state, vm)

                    // 4. Show
                    Button(onClick = { vm.showTransactions() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Show Transactions")
                    }

                    // 5. Totals
                    state.totals?.let { TotalsSection(it) }

                    if (state.applied != null && state.undated.isNotEmpty()) {
                        UndatedNotice(
                            count = state.undated.size,
                            expanded = showUndated,
                            onToggle = { showUndated = !showUndated },
                            onConfirmYears = { vm.confirmYearsFromReceivedDate() },
                        )
                    }
                }
            }

            if (showUndated && state.applied != null) {
                items(state.undated, key = { "u" + it.id }) { t ->
                    UndatedRow(t) { editing = t }
                }
            }

            if (state.applied != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${state.rows.size} transaction(s) – tap a row to see the SMS / correct it",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = {
                            val f = state.applied!!
                            exportLauncher.launch("transactions_${f.start}_${f.end}.csv")
                        }) { Text("Export CSV / Excel") }
                    }
                    Spacer(Modifier.height(8.dp))
                    TableHeader(hScroll)
                }
                if (state.rows.isEmpty()) {
                    item {
                        Text(
                            "No transactions with a confirmed date for this sender selection and date range.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(state.rows, key = { it.id }) { t ->
                    TableRow(t, hScroll) { editing = t }
                }
            }
        }
    }

    if (showSenderDialog) {
        SenderDialog(
            senders = state.senders,
            selected = state.selectedSenders,
            loading = state.busy,
            onDone = { vm.setSelectedSenders(it); showSenderDialog = false },
            onDismiss = { showSenderDialog = false },
        )
    }
    if (showPasteDialog) {
        PasteDialog(
            onImport = { text, label -> vm.importPasted(text, label); showPasteDialog = false },
            onDismiss = { showPasteDialog = false },
        )
    }
    editing?.let { t ->
        EditDialog(
            t = t,
            onSave = { vm.saveEdit(it); editing = null },
            onDelete = { vm.delete(it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

private fun hasSmsPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SenderSection(state: UiState, onChoose: () -> Unit, onChooseWithoutSms: () -> Unit, onPaste: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Select SMS Sender", fontWeight = FontWeight.Bold)
            val summary = when {
                state.selectedSenders == null -> "All Senders"
                state.selectedSenders.isEmpty() -> "None selected"
                else -> state.selectedSenders.sorted().joinToString(", ")
            }
            Text("Selected: $summary", maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChoose) { Text("Choose sender(s)") }
                OutlinedButton(onClick = onPaste) { Text("Paste SMS manually") }
            }
            if (!state.sendersLoadedFromSms && state.senders.isNotEmpty()) {
                TextButton(onClick = onChooseWithoutSms) { Text("Choose from already imported senders only") }
            }
        }
    }
}

@Composable
private fun DateSection(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Date range", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start date", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = { pickDate(context, state.start) { vm.setStart(it) } }, modifier = Modifier.fillMaxWidth()) {
                        Text(formatDate(state.start))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("End date", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = { pickDate(context, state.end) { vm.setEnd(it) } }, modifier = Modifier.fillMaxWidth()) {
                        Text(formatDate(state.end))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { val t = LocalDate.now(); vm.setRange(t, t) }) { Text("Today") }
                TextButton(onClick = {
                    val t = LocalDate.now()
                    vm.setRange(t.withDayOfMonth(1), t.withDayOfMonth(t.lengthOfMonth()))
                }) { Text("This month") }
                TextButton(onClick = { pickDate(context, state.start) { vm.setRange(it, it) } }) { Text("Single day…") }
            }
        }
    }
}

private fun pickDate(context: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth,
    ).show()
}

@Composable
private fun TotalsSection(totals: Totals) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Totals by type (${totals.transactionCount} transactions)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (expanded) "Hide ▲" else "Show ▼", color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                HorizontalDivider()
                for (type in TxType.entries) {
                    val rows = totals.byType.filter { it.type == type }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(type.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Column(horizontalAlignment = Alignment.End) {
                            if (rows.isEmpty()) {
                                Text("0", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                for (r in rows) {
                                    Text(
                                        "${r.count} × · ${formatMoney(r.total, r.currency)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                GrandTotal("Total incoming transfers", totals.incomingByCurrency)
                GrandTotal("Total outgoing (transfers, purchases, withdrawals, fees)", totals.outgoingByCurrency)
                Text(
                    "Incoming and outgoing are shown separately on purpose – they are not a balance or profit. " +
                        "Deposits, refunds and unknown items are listed above only. Currencies are never added together.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (totals.missingAmountCount > 0) {
                    Text(
                        "${totals.missingAmountCount} transaction(s) have no amount/currency and are not in the totals – tap them in the table to fix.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun GrandTotal(label: String, values: Map<String, java.math.BigDecimal>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Column(horizontalAlignment = Alignment.End) {
            if (values.isEmpty()) Text("0", fontWeight = FontWeight.Bold)
            for ((cur, v) in values) Text(formatMoney(v, cur), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UndatedNotice(count: Int, expanded: Boolean, onToggle: () -> Unit, onConfirmYears: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "⚠ $count transaction(s) from the selected sender(s) have no confirmed date (for example the SMS has " +
                    "day and month but no year). They are NOT included in the table or totals until the date is confirmed.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggle) { Text(if (expanded) "Hide them" else "Review them") }
                OutlinedButton(onClick = onConfirmYears) { Text("Use SMS received year") }
            }
        }
    }
}

@Composable
private fun UndatedRow(t: Transaction, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            "${t.dateText ?: "no date"} ${t.time ?: ""} · ${t.type.english} · ${formatMoney(t.amount, t.currency)} · ${t.sender}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(t.originalText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

// ---- table --------------------------------------------------------------------------------

private val columns: List<Pair<String, Dp>> = listOf(
    "Date" to 100.dp, "Time" to 60.dp, "Action" to 100.dp, "Type" to 150.dp, "Amount" to 110.dp,
    "Currency" to 70.dp, "Sender/person" to 170.dp, "Recipient/person" to 170.dp, "Merchant" to 150.dp,
    "Acct/card" to 80.dp, "Reference" to 130.dp, "Avail. balance" to 130.dp,
)

@Composable
private fun TableHeader(hScroll: ScrollState) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).horizontalScroll(hScroll)
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        for ((title, w) in columns) Cell(title, w, bold = true)
    }
}

@Composable
private fun TableRow(t: Transaction, hScroll: ScrollState, onClick: () -> Unit) {
    val bg = if (t.needsReview) Color(0x33FFC107) else Color.Transparent
    val values = listOf(
        (if (t.needsReview) "⚠ " else "") + (t.date?.toString() ?: ""),
        t.time ?: "",
        t.action ?: "",
        t.type.label,
        formatAmount(t.amount),
        t.currency ?: "",
        t.fromParty ?: "",
        t.toParty ?: "",
        t.merchant ?: "",
        t.accountSuffix ?: "",
        t.reference ?: "",
        formatMoney(t.balance, t.balanceCurrency),
    )
    Column {
        Row(
            Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).horizontalScroll(hScroll)
                .padding(vertical = 8.dp, horizontal = 8.dp)
        ) {
            values.forEachIndexed { i, v -> Cell(v, columns[i].second, bold = i == 4) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun Cell(text: String, width: Dp, bold: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(width).padding(end = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
