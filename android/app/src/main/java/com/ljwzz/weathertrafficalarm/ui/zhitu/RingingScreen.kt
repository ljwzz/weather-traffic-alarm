package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljwzz.weathertrafficalarm.R

private val RingingWhite = Color.White
private val RingingFooter = Color(0xFFC5DFDE)
private val RingingBrand = Color(0xFF007F78)
private val RingingSurface = Color.White.copy(alpha = 0.13f)
private val RingingStroke = Color.White.copy(alpha = 0.20f)
@OptIn(ExperimentalTextApi::class)
private val RingingRoboto = FontFamily(
    Font(
        R.font.roboto_variable,
        weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(300),
            FontVariation.width(100f),
        ),
    ),
)

/**
 * Full-screen alarm presentation. Business actions are delegated to the owner;
 * this composable does not schedule alarms, play audio, or access network state.
 */
@Composable
fun RingingScreen(
    state: RingingUiState,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onOpenPlans: () -> Unit,
    onClose: () -> Unit,
) {
    val isRingingAction = state.phase == RingingPhase.RINGING
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().testTag("ringing_screen")) {
        Image(
            painter = painterResource(R.drawable.ringing_wallpaper_image),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .testTag("ringing_content"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(38.dp))
            RingingHeader(state.header)
            Spacer(Modifier.height(30.dp))
            RingingTimeBlock(state)
            RingingReasonCard(state)
            Spacer(Modifier.height(104.dp))
            RingingActions(
                state = state,
                isRingingAction = isRingingAction,
                onDismiss = onDismiss,
                onSnooze = onSnooze,
                onOpenPlans = onOpenPlans,
                onClose = onClose,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (state.busy) "正在处理" else state.footer,
                modifier = Modifier.fillMaxWidth().testTag("ringing_feedback"),
                color = RingingFooter,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
            state.errorMessage?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ringing_error"),
                    color = RingingWhite,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RingingHeader(header: String) {
    Row(
        modifier = Modifier.heightIn(min = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_figma_snooze),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = RingingWhite,
        )
        Text(
            text = header,
            color = RingingWhite,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RingingTimeBlock(state: RingingUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 364.dp)
            .heightIn(min = 216.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.dateLabel,
            color = RingingFooter,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.alarmTime,
            color = RingingWhite,
            fontFamily = RingingRoboto,
            fontWeight = FontWeight.Light,
            autoSize = TextAutoSize.StepBased(minFontSize = 48.sp, maxFontSize = 94.sp),
            fontSize = 94.sp,
            lineHeight = 107.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .widthIn(min = 208.dp, max = 208.dp)
                .heightIn(min = 30.dp)
                .clip(RoundedCornerShape(50))
                .background(RingingSurface)
                .border(1.dp, RingingStroke, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.badge,
                color = RingingWhite,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun RingingReasonCard(state: RingingUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 364.dp)
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(RingingSurface)
            .border(1.dp, RingingStroke, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Text(
            text = state.reasonTitle,
            color = RingingWhite,
            fontSize = 17.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.reason,
            color = RingingWhite,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RingingActions(
    state: RingingUiState,
    isRingingAction: Boolean,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onOpenPlans: () -> Unit,
    onClose: () -> Unit,
) {
    val enabled = !state.busy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 364.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = if (isRingingAction) onDismiss else onOpenPlans,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .testTag(if (isRingingAction) "ringing_dismiss" else "ringing_open_plans"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RingingWhite,
                contentColor = RingingBrand,
                disabledContainerColor = RingingWhite.copy(alpha = 0.55f),
                disabledContentColor = RingingBrand.copy(alpha = 0.55f),
            ),
        ) {
            Text(
                text = if (isRingingAction) "停止" else "返回闹钟",
                fontSize = 20.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Button(
            onClick = if (isRingingAction) onSnooze else onClose,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(if (isRingingAction) "ringing_snooze" else "ringing_close"),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, RingingStroke),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.14f),
                contentColor = RingingWhite,
                disabledContainerColor = Color.White.copy(alpha = 0.08f),
                disabledContentColor = RingingWhite.copy(alpha = 0.55f),
            ),
        ) {
            if (isRingingAction) {
                Icon(
                    painter = painterResource(R.drawable.ic_figma_snooze),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = RingingWhite,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "贪睡 ${state.snoozeMinutes} 分钟",
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            } else {
                Text(text = "关闭", fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
    }
}
