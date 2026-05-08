/**
 * ChatScreen.kt
 * Responsibility : AI Artist chat UI — message list + streaming input field.
 * API calls      : POST /api/v1/chat (streaming, via ChatViewModel)
 * Injects        : ChatViewModel (hiltViewModel)
 */
package com.artgrid.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.domain.chat.model.ChatMessage
import com.artgrid.mobile.domain.chat.model.ChatRole
import com.artgrid.mobile.ui.common.EmptyScreen

// ── Screen composable ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text       = "Artist Assistant",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text  = "Powered by Gemini 2.5 Flash",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        ChatContent(
            uiState         = uiState,
            onInputChanged  = viewModel::onInputChanged,
            onSend          = { viewModel.sendMessage() },
            modifier        = Modifier.padding(innerPadding),
        )
    }
}

// ── Content composable (pure data + lambdas) ──────────────────────────────────

@Composable
private fun ChatContent(
    uiState: ChatUiState,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to the latest message whenever the list grows.
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // ── Daily limit banner ────────────────────────────────────────────────
        if (uiState.dailyLimitHit) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text     = "Daily limit of 50 messages reached. Resets at midnight UTC.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // ── Message list ──────────────────────────────────────────────────────
        if (uiState.messages.isEmpty()) {
            EmptyScreen(
                message  = "Ask anything about your painting —\ncolour mixing, composition, technique.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state           = listState,
                modifier        = Modifier.weight(1f),
                contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    ChatBubble(message = message)
                }
            }
        }

        // ── Input bar ─────────────────────────────────────────────────────────
        ChatInputBar(
            text       = uiState.inputText,
            enabled    = !uiState.isStreaming && !uiState.dailyLimitHit,
            onChanged  = onInputChanged,
            onSend     = onSend,
            modifier   = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER

    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        ElevatedCard(
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser) {
                    Text(
                        text       = "ArtGrid AI",
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text  = if (message.isStreaming && message.text.isEmpty()) "▌" else message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Streaming cursor suffix while response is being generated.
                if (message.isStreaming && message.text.isNotEmpty()) {
                    Text(
                        text  = "▌",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    enabled: Boolean,
    onChanged: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value       = text,
            onValueChange = onChanged,
            modifier    = Modifier.weight(1f),
            enabled     = enabled,
            placeholder = { Text("Ask about colour, technique…") },
            maxLines    = 4,
            shape       = MaterialTheme.shapes.large,
        )
        IconButton(
            onClick  = onSend,
            enabled  = enabled && text.isNotBlank(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Outlined.Send,
                contentDescription = "Send",
                tint               = if (enabled && text.isNotBlank())
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
