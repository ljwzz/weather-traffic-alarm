package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Read-only rendering of a persisted evaluation decision.
 *
 * This composable deliberately owns no repository, provider, or scheduling dependency. Its
 * caller resolves [DecisionDetailUi] by decisionId or occurrenceId before composition, so simply
 * opening, returning to, or refreshing this surface cannot evaluate a plan or change an alarm.
 */
@Composable
fun DecisionDetailScreen(
    detail: DecisionDetailUi,
    onBack: () -> Unit,
    onReevaluate: () -> Unit,
    reevaluateInProgress: Boolean,
    reevaluateFeedback: String?,
    onCredentials: () -> Unit,
    onOnboarding: () -> Unit,
    onCommute: () -> Unit,
    onDiagnostics: () -> Unit,
    onRefresh: () -> Unit = {},
    nextRetryLabel: String? = null,
    currentPlanMessage: String? = null,
    onHistory: () -> Unit = {},
) {
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = {
            ZhituTopBar(
                title = if (detail.available) "本次决策" else "决策详情",
                subtitle = detail.takeIf { it.available }?.let { "${it.planName} · ${it.targetDate}" },
                navigation = onBack,
            )
        },
    ) { padding ->
        if (!detail.available) {
            DecisionUnavailable(
                message = detail.unavailableMessage ?: "本次决策记录不可用。",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("decision-detail-${detail.decisionId.orEmpty()}"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DecisionConclusionCard(detail) }
            item { DecisionTimingCard(detail) }
            item { DecisionApplicationCard(detail) }
            item { DecisionSourcesCard(detail) }
            item {
                DecisionCurrentPlanActions(
                    detail = detail,
                    onReevaluate = onReevaluate,
                    reevaluateInProgress = reevaluateInProgress,
                    feedback = reevaluateFeedback,
                    currentPlanMessage = currentPlanMessage,
                    nextRetryLabel = nextRetryLabel,
                    onCredentials = onCredentials,
                    onOnboarding = onOnboarding,
                    onCommute = onCommute,
                    onDiagnostics = onDiagnostics,
                    onRefresh = onRefresh,
                    onHistory = onHistory,
                )
            }
        }
    }
}

@Composable
private fun DecisionUnavailable(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("无法显示本次决策", color = ZhituColors.Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(message, modifier = Modifier.padding(top = 8.dp), color = ZhituColors.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DecisionConclusionCard(detail: DecisionDetailUi) {
    val requiresAttention = detail.evaluationTone == DecisionDetailTone.WARNING ||
        detail.title in setOf("提前提醒注册失败", "提前额度不足") ||
        detail.applicationLabel.startsWith("注册失败")
    val cardColor = if (requiresAttention) ZhituColors.AmberBackground else ZhituColors.Navy
    val foreground = if (requiresAttention) ZhituColors.Ink else Color.White
    val muted = if (requiresAttention) ZhituColors.Amber else Color(0xFFA6C6CE)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(detail.planName, color = foreground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detail.title, color = foreground, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusBadge(if (requiresAttention) "需处理" else detail.evaluationTone.shortLabel(), bright = !requiresAttention)
            }
            Text(detail.evaluationLabel, color = muted, style = MaterialTheme.typography.bodyMedium)
            detail.failureReason?.let { Text(it, color = ZhituColors.Amber, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
            Text(detail.applicationLabel, color = if (requiresAttention) ZhituColors.Ink else muted, style = MaterialTheme.typography.bodyMedium)
            if (requiresAttention) {
                Text("基础响铃 ${detail.baseWake}", color = foreground, style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    HeroWakeTime("基础响铃", detail.baseWake, muted)
                    Text("→", color = muted, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
                    HeroWakeTime("建议响铃", detail.recommendedWake, foreground)
                }
            }
            Text("评估时区 · ${detail.zoneLabel}", color = muted, style = MaterialTheme.typography.labelSmall)
            Text("${detail.targetDate} · ${detail.evaluatedAt}", color = muted, style = MaterialTheme.typography.labelSmall)
            detail.expiredNotice?.let { Text(it, color = muted, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun RowScope.HeroWakeTime(label: String, time: String, color: Color) = Column(Modifier.weight(1f)) {
    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    Text(time, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
}

@Composable
private fun DecisionTimingCard(detail: DecisionDetailUi) = FormCard {
    SectionTitle("时间是这样算出来的")
    DetailLine("预计出发", detail.departure)
    DetailLine("通勤耗时", detail.commute)
    DetailLine("准备时长", detail.preparation)
    DetailLine("天气等级", detail.weather)
    DetailLine("天气缓冲", detail.weatherBuffer)
    DetailLine("日期规则", detail.dayRule)
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ZhituColors.Mint),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TimePoint("基础响铃", detail.baseWake)
            TimePoint("建议响铃", detail.recommendedWake)
            TimePoint("实际应用", detail.actualWake)
        }
    }
}

@Composable
private fun RowScope.TimePoint(label: String, time: String) = Column(Modifier.weight(1f)) {
    Text(time, color = ZhituColors.Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
    Text(label, color = ZhituColors.Brand, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun DetailLine(label: String, value: String) = Row(
    Modifier.fillMaxWidth().padding(top = 10.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
) {
    Text(label, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.width(16.dp))
    Text(value, color = ZhituColors.Ink, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
}

@Composable
private fun DecisionApplicationCard(detail: DecisionDetailUi) = FormCard {
    SectionTitle("调度与当前状态")
    DetailLine("本次应用结果", detail.applicationLabel)
    DetailLine("实例当前状态", detail.occurrenceLabel)
    detail.fallbackReason?.let { DetailLine("降级说明", it) }
    detail.attemptLabel?.let { DetailLine("评估尝试", it) }
    Text(
        "评估结果、调度应用和实例状态分别记录；查看本页不会改变本机闹钟。",
        color = ZhituColors.Muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun DecisionSourcesCard(detail: DecisionDetailUi) {
    var expanded by remember(detail.decisionId) { mutableStateOf(false) }
    FormCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag("decision-detail-sources"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("数据来源与更新时间", color = ZhituColors.Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", color = ZhituColors.Brand, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            if (detail.sourceLines.isEmpty()) Text("本次未提供数据来源。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            detail.sourceLines.forEach { source -> Text(source, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun DecisionCurrentPlanActions(
    detail: DecisionDetailUi,
    onReevaluate: () -> Unit,
    reevaluateInProgress: Boolean,
    feedback: String?,
    currentPlanMessage: String?,
    nextRetryLabel: String?,
    onCredentials: () -> Unit,
    onOnboarding: () -> Unit,
    onCommute: () -> Unit,
    onDiagnostics: () -> Unit,
    onRefresh: () -> Unit,
    onHistory: () -> Unit,
) = FormCard {
    SectionTitle("重新评估当前计划")
    Text("重新评估会针对当前有效的计划配置创建新记录，不会改写正在查看的历史记录。", color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    currentPlanMessage?.let { Text(it, color = ZhituColors.Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    nextRetryLabel?.let { Text(it, color = ZhituColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    Button(
        onClick = onReevaluate,
        enabled = !reevaluateInProgress,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).testTag("decision-detail-reevaluate"),
        colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
    ) { Text(if (reevaluateInProgress) "正在发起评估…" else "重新评估") }
    feedback?.let { DetailFeedback(it) }
    if (detail.recoveryActions.isNotEmpty()) {
        Text("恢复入口", color = ZhituColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            detail.recoveryActions.forEach { action ->
                when (action) {
                    DecisionRecoveryAction.CREDENTIALS -> RecoveryButton("凭据", onCredentials, Modifier.weight(1f))
                    DecisionRecoveryAction.ONBOARDING -> RecoveryButton("授权", onOnboarding, Modifier.weight(1f))
                    DecisionRecoveryAction.COMMUTE -> RecoveryButton("通勤", onCommute, Modifier.weight(1f))
                    DecisionRecoveryAction.DIAGNOSTICS -> RecoveryButton("诊断", onDiagnostics, Modifier.weight(1f))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onRefresh, modifier = Modifier.testTag("decision-detail-refresh")) { Text("重新读取本地记录") }
        TextButton(onClick = onHistory, modifier = Modifier.testTag("decision-detail-history")) { Text("查看决策记录") }
    }
}

@Composable
private fun RecoveryButton(label: String, onClick: () -> Unit, modifier: Modifier) = TextButton(onClick = onClick, modifier = modifier.testTag("decision-detail-recovery-$label")) { Text(label) }

@Composable
private fun DetailFeedback(message: String) = Text(message, color = ZhituColors.Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp).testTag("decision-detail-feedback"))

private fun DecisionDetailTone.shortLabel(): String = when (this) {
    DecisionDetailTone.POSITIVE -> "已完成"
    DecisionDetailTone.WARNING -> "需处理"
    DecisionDetailTone.NEUTRAL -> "已记录"
}
