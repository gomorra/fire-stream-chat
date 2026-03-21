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
) {
    // Only CHATS and CALLS are swipeable; LISTS is not yet implemented.
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            BottomNavBar(
                selectedTab = MainTab.entries[pagerState.currentPage],
                onChatsClick = { scope.launch { pagerState.animateScrollToPage(MainTab.CHATS.ordinal) } },
                onCallsClick = { scope.launch { pagerState.animateScrollToPage(MainTab.CALLS.ordinal) } }
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
                MainTab.CALLS -> CallsScreen()
                MainTab.LISTS -> Unit
            }
        }
    }
}
