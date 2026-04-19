package com.firestream.chat.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal enum class MainTab { CHATS, CALLS, LISTS }

@Composable
internal fun BottomNavBar(
    selectedTab: MainTab,
    onChatsClick: () -> Unit,
    onCallsClick: () -> Unit,
    onListsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp
    ) {
        NavItem(
            selected = selectedTab == MainTab.CHATS,
            onClick = onChatsClick,
            selectedIcon = Icons.AutoMirrored.Filled.Chat,
            unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
            label = "Chats",
            enabled = true
        )
        NavItem(
            selected = selectedTab == MainTab.CALLS,
            onClick = onCallsClick,
            selectedIcon = Icons.Filled.Phone,
            unselectedIcon = Icons.Outlined.Phone,
            label = "Calls",
            enabled = true
        )
        NavItem(
            selected = selectedTab == MainTab.LISTS,
            onClick = onListsClick,
            selectedIcon = Icons.AutoMirrored.Filled.List,
            unselectedIcon = Icons.AutoMirrored.Outlined.List,
            label = "Lists",
            enabled = true
        )
    }
}

@Composable
private fun RowScope.NavItem(
    selected: Boolean,
    onClick: () -> Unit,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String,
    enabled: Boolean,
    showComingSoon: Boolean = false
) {
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconScale"
    )
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    val enabledUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint by animateColorAsState(
        targetValue = when {
            !enabled -> disabledColor
            selected -> MaterialTheme.colorScheme.primary
            else -> enabledUnselectedColor
        },
        label = "iconTint"
    )

    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        icon = {
            BadgedBox(
                badge = {
                    if (showComingSoon) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(text = "Soon", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = if (selected) selectedIcon else unselectedIcon,
                    contentDescription = label,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                    tint = iconTint
                )
            }
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = iconTint
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = enabledUnselectedColor,
            unselectedTextColor = enabledUnselectedColor,
            disabledIconColor = disabledColor,
            disabledTextColor = disabledColor
        )
    )
}
