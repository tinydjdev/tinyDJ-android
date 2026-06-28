package com.tinydj.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinydj.ui.library.LibraryView
import com.tinydj.ui.theme.Body
import com.tinydj.ui.theme.Ink

enum class AppTab { PLAYER, LIBRARY, SETTINGS }

@Composable
fun MainTabScreen(vm: DeckViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(AppTab.PLAYER) }

    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Body)
                    .navigationBarsPadding()
                    .height(52.dp)
                    .drawBehind {
                        // Top boundary line
                        drawLine(
                            color = Ink,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                        // Bottom boundary line
                        drawLine(
                            color = Ink,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                        // Left boundary line (left of PLAYER)
                        drawLine(
                            color = Ink,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                        // Right boundary line (right of SETTINGS)
                        drawLine(
                            color = Ink,
                            start = Offset(size.width, 0f),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
            ) {
                AppTab.values().forEachIndexed { index, tab ->
                    val active = currentTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (active) Ink else Body)
                            .clickable { currentTab = tab }
                            .drawBehind {
                                // Separator line on the right side of the tab
                                if (index < AppTab.values().size - 1) {
                                    drawLine(
                                        color = if (active) Body else Ink,
                                        start = Offset(size.width, 0f),
                                        end = Offset(size.width, size.height),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.name,
                            color = if (active) Body else Ink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppTab.PLAYER -> {
                    DeckScreen(vm = vm)
                }
                AppTab.LIBRARY -> {
                    LibraryView(
                        state = state,
                        vm = vm,
                        onTrackSelected = { track ->
                            vm.selectTrack(track)
                            currentTab = AppTab.PLAYER
                        }
                    )
                }
                AppTab.SETTINGS -> {
                    SettingsView(
                        state = state,
                        vm = vm
                    )
                }
            }
        }
    }
}
