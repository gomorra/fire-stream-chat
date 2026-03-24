package com.firestream.chat.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.firestream.chat.ui.calls.CallsScreen
import com.firestream.chat.ui.chatlist.ChatListScreen
import com.firestream.chat.ui.lists.ListsScreen
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
    onMessageClick: (chatId: String, recipientId: String) -> Unit,
    onListClick: (listId: String) -> Unit = {},
    onListCreated: (listId: String) -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

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
                )
                MainTab.CALLS -> CallsScreen(onMessageClick = onMessageClick)
                MainTab.LISTS -> ListsScreen(onListClick = onListClick, onListCreated = onListCreated)
            }
        }
    }
}
