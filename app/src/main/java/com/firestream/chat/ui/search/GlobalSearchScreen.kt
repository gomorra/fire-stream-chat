package com.firestream.chat.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageSearchFilter

/**
 * Search across every chat.
 *
 * Its own NavHost destination rather than a panel over the chat list: the chat
 * list is a page inside `MainScreen`'s `HorizontalPager`, and the filter chip
 * row is a `LazyRow` that would fight the pager for horizontal drags. A
 * destination also gets system back and a real keyboard-on-entry for free.
 *
 * Tapping any result — media tiles included — lands in the conversation at that
 * message. A cross-chat fullscreen media pager would need gallery arguments
 * spanning chats, and for a global hit "take me to where this was said" is the
 * more useful answer anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onBackClick: () -> Unit,
    onResultClick: (chatId: String, recipientId: String, messageId: String) -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // Search is what this destination is for, so it opens ready to type.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("Search messages…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (uiState.query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearQuery) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear query")
                                }
                            }
                        },
                        // Borderless: the top app bar is already the field's
                        // container, and a second outline inside it reads as a
                        // box within a box.
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchFilterChipRow(
                filter = uiState.filter,
                onFilterChange = viewModel::onFilterChange,
            )

            if (uiState.results.isEmpty()) {
                SearchEmptyState(
                    query = uiState.query,
                    isSelecting = uiState.isSelecting,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // The active filters live here as well as in the chip row:
                // chips scroll off-screen, and an active-but-invisible filter is
                // exactly the dishonesty this summary exists to prevent.
                SearchResultsSummary(
                    summary = searchResultsSummary(
                        filter = uiState.filter,
                        resultCount = uiState.results.size,
                        // Reported by the layer that saw the raw row count, not
                        // inferred from the survivors here: the whole-word pass
                        // runs after SQLite's LIMIT, so a truncated page can come
                        // back well short of the cap.
                        atLimit = uiState.truncated,
                    ),
                    showClear = uiState.filter.isActive,
                    onClearFilters = { viewModel.onFilterChange(MessageSearchFilter.NONE) },
                )
                val openResult: (Message) -> Unit = { message ->
                    onResultClick(message.chatId, uiState.recipientIdFor(message.chatId), message.id)
                }
                SearchResultList(
                    results = uiState.results,
                    filterType = uiState.filter.type,
                    resultLabel = uiState::resultLabel,
                    onResultClick = openResult,
                    onMediaClick = openResult,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}
