package io.nurunuru.app.ui.miniapps

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Scheduler mini-app for calendar event management (Chronostr).
 * Synced with web version: components/miniapps/SchedulerApp.js
 *
 * Supports Chronostr event kinds:
 * - 31928: Parent schedule event (date-based calendar)
 * - 31927: Time-based calendar event
 * - 31926: Date candidate (child event)
 * - 31925: Calendar RSVP
 */

private data class ScheduleEvent(
    val event: NostrEvent,
    val title: String,
    val description: String,
    val dates: List<DateCandidate>,
    val rsvps: Map<String, List<String>>,  // date -> list of pubkeys
    val dTag: String
)

private data class DateCandidate(
    val date: String,
    val label: String,
    val eventId: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerApp(
    pubkey: String,
    repository: NostrRepository
) {
    val nuruColors = LocalNuruColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var events by remember { mutableStateOf<List<ScheduleEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<ScheduleEvent?>(null) }

    // Load existing schedule events
    LaunchedEffect(Unit) {
        scope.launch {
            loading = true
            try {
                val rawEvents = repository.fetchEvents(
                    kinds = listOf(NostrKind.CHRONOSTR_EVENT),
                    authors = listOf(pubkey),
                    limit = 20
                )

                events = rawEvents.mapNotNull { event ->
                    val title = event.tags.firstOrNull { it.firstOrNull() == "title" }?.getOrNull(1)
                        ?: event.tags.firstOrNull { it.firstOrNull() == "name" }?.getOrNull(1)
                        ?: return@mapNotNull null
                    val description = event.content
                    val dTag = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""

                    // Parse date candidates from tags
                    val dates = event.tags
                        .filter { it.firstOrNull() == "date" && it.size >= 2 }
                        .map { tag ->
                            DateCandidate(
                                date = tag[1],
                                label = tag.getOrNull(2) ?: tag[1]
                            )
                        }

                    ScheduleEvent(
                        event = event,
                        title = title,
                        description = description,
                        dates = dates,
                        rsvps = emptyMap(),
                        dTag = dTag
                    )
                }
            } catch (_: Exception) { }
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        // Header with create button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "調整くん",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = nuruColors.textPrimary
            )
            FilledTonalButton(
                onClick = { showCreateDialog = true },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = LineGreen)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新規作成", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LineGreen)
                }
            }
            events.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("予定がありません", color = nuruColors.textTertiary, fontSize = 16.sp)
                        Text("「新規作成」ボタンから予定を作成できます",
                            color = nuruColors.textTertiary, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(events) { event ->
                        ScheduleEventCard(
                            event = event,
                            nuruColors = nuruColors,
                            onClick = { selectedEvent = event }
                        )
                    }
                }
            }
        }
    }

    // Create Dialog
    if (showCreateDialog) {
        CreateScheduleDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, description, dates ->
                scope.launch {
                    try {
                        val dTag = UUID.randomUUID().toString().take(8)
                        val tags = mutableListOf(
                            listOf("d", dTag),
                            listOf("title", title)
                        )
                        dates.forEach { date ->
                            tags.add(listOf("date", date))
                        }

                        repository.publishEvent(
                            kind = NostrKind.CHRONOSTR_EVENT,
                            content = description,
                            tags = tags
                        )

                        Toast.makeText(context, "予定を作成しました", Toast.LENGTH_SHORT).show()
                        showCreateDialog = false

                        // Reload
                        loading = true
                        val rawEvents = repository.fetchEvents(
                            kinds = listOf(NostrKind.CHRONOSTR_EVENT),
                            authors = listOf(pubkey),
                            limit = 20
                        )
                        events = rawEvents.mapNotNull { event ->
                            val t = event.tags.firstOrNull { it.firstOrNull() == "title" }?.getOrNull(1)
                                ?: return@mapNotNull null
                            val d = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""
                            val dateCandidates = event.tags
                                .filter { it.firstOrNull() == "date" && it.size >= 2 }
                                .map { DateCandidate(it[1], it.getOrNull(2) ?: it[1]) }

                            ScheduleEvent(event, t, event.content, dateCandidates, emptyMap(), d)
                        }
                        loading = false
                    } catch (e: Exception) {
                        Toast.makeText(context, "作成に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Detail view
    selectedEvent?.let { event ->
        ScheduleDetailDialog(
            event = event,
            pubkey = pubkey,
            repository = repository,
            onDismiss = { selectedEvent = null }
        )
    }
}

@Composable
private fun ScheduleEventCard(
    event: ScheduleEvent,
    nuruColors: io.nurunuru.app.ui.theme.NuruColors,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = nuruColors.bgSecondary)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                event.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = nuruColors.textPrimary
            )
            if (event.description.isNotBlank()) {
                Text(
                    event.description,
                    fontSize = 13.sp,
                    color = nuruColors.textSecondary,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (event.dates.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    event.dates.take(3).forEach { date ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = nuruColors.bgTertiary
                        ) {
                            Text(
                                date.label,
                                fontSize = 11.sp,
                                color = nuruColors.textSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (event.dates.size > 3) {
                        Text(
                            "+${event.dates.size - 3}",
                            fontSize = 11.sp,
                            color = nuruColors.textTertiary,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
            Text(
                formatTimestamp(event.event.createdAt),
                fontSize = 11.sp,
                color = nuruColors.textTertiary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun CreateScheduleDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String, dates: List<String>) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var dateInput by remember { mutableStateOf("") }
    var dates by remember { mutableStateOf<List<String>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = nuruColors.bgPrimary,
        title = { Text("予定を作成", color = nuruColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("タイトル") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("説明（任意）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                // Date candidates
                Text("候補日", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nuruColors.textPrimary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = dateInput,
                        onValueChange = { dateInput = it },
                        label = { Text("例: 3/15") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (dateInput.isNotBlank()) {
                                dates = dates + dateInput.trim()
                                dateInput = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "追加", tint = LineGreen)
                    }
                }

                dates.forEach { date ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(date, fontSize = 14.sp, color = nuruColors.textPrimary)
                        IconButton(
                            onClick = { dates = dates - date },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "削除",
                                tint = nuruColors.textTertiary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, description, dates) },
                enabled = title.isNotBlank()
            ) {
                Text("作成", color = if (title.isNotBlank()) LineGreen else nuruColors.textTertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル", color = nuruColors.textSecondary)
            }
        }
    )
}

@Composable
private fun ScheduleDetailDialog(
    event: ScheduleEvent,
    pubkey: String,
    repository: NostrRepository,
    onDismiss: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rsvpDates by remember { mutableStateOf<Set<String>>(emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = nuruColors.bgPrimary,
        title = { Text(event.title, color = nuruColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.description.isNotBlank()) {
                    Text(event.description, fontSize = 14.sp, color = nuruColors.textSecondary)
                }

                Spacer(Modifier.height(8.dp))
                Text("候補日を選択", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nuruColors.textPrimary)

                event.dates.forEach { date ->
                    val isSelected = rsvpDates.contains(date.date)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                rsvpDates = if (isSelected) rsvpDates - date.date
                                           else rsvpDates + date.date
                            }
                            .background(
                                if (isSelected) LineGreen.copy(alpha = 0.1f)
                                else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(date.label, fontSize = 14.sp, color = nuruColors.textPrimary)
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null,
                                tint = LineGreen, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        try {
                            val tags = mutableListOf(
                                listOf("a", "31928:${event.event.pubkey}:${event.dTag}")
                            )
                            rsvpDates.forEach { date ->
                                tags.add(listOf("date", date))
                            }

                            repository.publishEvent(
                                kind = NostrKind.CALENDAR_RSVP,
                                content = "参加可能",
                                tags = tags
                            )
                            Toast.makeText(context, "回答を送信しました", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (_: Exception) {
                            Toast.makeText(context, "送信に失敗しました", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = rsvpDates.isNotEmpty()
            ) {
                Text("回答する", color = if (rsvpDates.isNotEmpty()) LineGreen else nuruColors.textTertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる", color = nuruColors.textSecondary)
            }
        }
    )
}

private fun formatTimestamp(epochSeconds: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
    return sdf.format(Date(epochSeconds * 1000))
}
