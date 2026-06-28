package com.tinydj.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinydj.ui.theme.Body
import com.tinydj.ui.theme.Ink
import kotlin.math.roundToInt

@Composable
fun SettingsView(
    state: DeckUiState,
    vm: DeckViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Body)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Title Bar
        Text(
            text = "SETTINGS",
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Device Name Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DEVICE NAME",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(130.dp)
            )
            BasicTextField(
                value = state.deviceName,
                onValueChange = { 
                    if (it.length <= 8) {
                        vm.setDeviceName(it)
                    }
                },
                textStyle = TextStyle(
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                ),
                cursorBrush = SolidColor(Ink),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Ink, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Settings items
        SettingEnumRow(
            label = "PLAY MODE",
            currentValue = state.playMode,
            options = PlayMode.values(),
            onSelected = { vm.setPlayMode(it) }
        )

        SettingEnumRow(
            label = "LED BRIGHTNESS",
            currentValue = state.leds,
            options = LedBrightness.values(),
            onSelected = { vm.setLedBrightness(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VIBRATION STRENGTH",
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${(state.vibrationStrength * 100).roundToInt()}%",
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = state.vibrationStrength,
                onValueChange = { vm.setVibrationStrength(it) },
                colors = SliderDefaults.colors(
                    thumbColor = Ink,
                    activeTrackColor = Ink,
                    inactiveTrackColor = Ink.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingEnumRow(
            label = "SOUND EFFECTS",
            currentValue = state.sfx,
            options = SfxMode.values(),
            onSelected = { vm.setSfxMode(it) }
        )

        SettingEnumRow(
            label = "MOTOR MODE",
            currentValue = state.motor,
            options = MotorMode.values(),
            onSelected = { vm.setMotorMode(it) }
        )

        SettingEnumRow(
            label = "MEMO QUALITY",
            currentValue = state.memoQuality,
            options = MemoQuality.values(),
            onSelected = { vm.setMemoQuality(it) }
        )

        SettingEnumRow(
            label = "SAMPLE RATE",
            currentValue = state.sampleRateMode,
            options = SampleRateMode.values(),
            onSelected = { vm.setSampleRateMode(it) },
            optionLabel = {
                when (it) {
                    SampleRateMode.R48 -> "48K"
                    SampleRateMode.R96 -> "96K"
                }
            }
        )

        SettingEnumRow(
            label = "EDIT MODE",
            currentValue = state.editMode,
            options = EditMode.values(),
            onSelected = { vm.setEditMode(it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // System info segment
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Ink, RoundedCornerShape(4.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "SYSTEM INFO",
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "FIRMWARE: v${state.firmwareVersion}",
                    color = Ink.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = "BATTERY: ${state.batteryPct}% ${if (state.charging) "(CHARGING)" else ""}",
                    color = Ink.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = "STORAGE: ${state.diskFreeGb}GB / ${state.diskTotalGb}GB FREE",
                    color = Ink.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun <T : Enum<T>> SettingEnumRow(
    label: String,
    currentValue: T,
    options: Array<T>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.name }
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val active = option == currentValue
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = if (active) Ink else Color.Transparent,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = Ink,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable { onSelected(option) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = optionLabel(option),
                        color = if (active) Body else Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
