package com.firestream.chat.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.ui.calls.CallsScreen
import com.firestream.chat.ui.chatlist.ChatListScreen
import com.firestream.chat.ui.lists.ListsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts the bottom-nav tabs (Chats / Calls) with a HorizontalPager so that
 * swiping between tabs physically moves the content under the user's finger,
 * matching the Safari-style page transition feel.
 */
@Composable
internal fun MainScreen(
    onChatClick: (chatId: String, recipientId: String) -> Unit,
    onNewChatClick: () -> Unit,
    onNewGroupClick: () -> Unit,
    onNewBroadcastClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onMessageClick: (chatId: String, recipientId: String) -> Unit,
    onListClick: (listId: String) -> Unit = {},
    onListCreated: (listId: String) -> Unit = {},
    deletedListTitle: String? = null,
    onDeletedListTitleConsumed: () -> Unit = {},
    preferencesDataStore: PreferencesDataStore? = null,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // A tab that has put a full-screen panel over itself (the Lists tab's chat
    // picker) locks the pager: the panel covers the page, so a sideways drag on
    // it would otherwise flip to the next tab and leave the panel open on top of
    // it. The pager is this screen's, so the lock is too — a tab swallowing the
    // drags itself would hide the conflict from the one composable that owns it.
    var tabOverlayOpen by remember { mutableStateOf(false) }

    // Restore the last-selected tab on first composition. DataStore is async,
    // so we read once and scroll instead of blocking initial render — the
    // CHAT_LIST route enter animation hides the brief first frame on page 0.
    LaunchedEffect(Unit) {
        val saved = preferencesDataStore?.lastTabIndexFlow?.first() ?: 0
        if (saved != pagerState.currentPage) pagerState.scrollToPage(saved)
    }

    // Persist every tab change so the next launch restores it.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .drop(1)
            .distinctUntilChanged()
            .collect { preferencesDataStore?.setLastTabIndex(it) }
    }

    Scaffold(
        bottomBar = {
            BottomNavBar(
                selectedTab = MainTab.entries[pagerState.currentPage],
                onChatsClick = { scope.launch { pagerState.animateScrollToPage(MainTab.CHATS.ordinal) } },
                onCallsClick = { scope.launch { pagerState.animateScrollToPage(MainTab.CALLS.ordinal) } },
                onListsClick = { scope.launch { pagerState.animateScrollToPage(MainTab.LISTS.ordinal) } }
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !tabOverlayOpen,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            beyondViewportPageCount = 1,
        ) { page ->
            when (MainTab.entries[page]) {
                MainTab.CHATS -> ChatListScreen(
                    onChatClick = onChatClick,
                    onNewChatClick = onNewChatClick,
                    onNewGroupClick = onNewGroupClick,
                    onNewBroadcastClick = onNewBroadcastClick,
                    onSettingsClick = onSettingsClick,
                    onSearchClick = onSearchClick,
                )
                MainTab.CALLS -> CallsScreen(onMessageClick = onMessageClick)
                MainTab.LISTS -> ListsScreen(
                    onListClick = onListClick,
                    onListCreated = onListCreated,
                    deletedListTitle = deletedListTitle,
                    onDeletedListTitleConsumed = onDeletedListTitleConsumed,
                    onOverlayVisibleChange = { tabOverlayOpen = it },
                )
            }
        }
    }
}
