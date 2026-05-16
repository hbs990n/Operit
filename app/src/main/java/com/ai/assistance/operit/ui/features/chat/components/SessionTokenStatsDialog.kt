package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.data.model.ContextBreakdown
import com.ai.assistance.operit.data.model.SessionTokenStats
import java.time.format.DateTimeFormatter

@Composable
fun SessionTokenStatsDialog(
    stats: SessionTokenStats,
    contextBreakdown: ContextBreakdown,
    userMessageCount: Int,
    assistantMessageCount: Int,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title
                Text(
                    text = stats.title.ifEmpty { "Session Stats" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                HorizontalDivider()

                // Session info
                StatsSection(title = "会话信息") {
                    StatsRow("提供商", stats.provider.ifEmpty { "-" })
                    StatsRow("模型", stats.modelName.ifEmpty { "-" })
                    StatsRow("上下文限制", formatTokenCount(stats.contextLimit))
                    StatsRow("用户消息", "$userMessageCount")
                    StatsRow("助手消息", "$assistantMessageCount")
                    StatsRow("API 调用", "${stats.apiCallCount}")
                }

                HorizontalDivider()

                // Token usage
                StatsSection(title = "Token 用量") {
                    StatsRow("总 Token", formatTokenCount(stats.totalTokens), highlight = true)
                    StatsRow("使用率", "${(stats.usageRate * 100).toInt()}%", highlight = stats.usageRate > 0.75f)
                    StatsRow("输入 Token", formatTokenCount(stats.inputTokens))
                    StatsRow("缓存 Token", formatTokenCount(stats.cachedInputTokens))
                    StatsRow("输出 Token", formatTokenCount(stats.outputTokens))
                    if (stats.reasoningTokens > 0) {
                        StatsRow("推理 Token", formatTokenCount(stats.reasoningTokens))
                    }
                    StatsRow("上下文窗口", formatTokenCount(stats.currentWindowSize))
                }

                HorizontalDivider()

                // Context breakdown
                StatsSection(title = "上下文分布") {
                    ContextBreakdownBar(contextBreakdown)
                    Spacer(modifier = Modifier.height(4.dp))
                    StatsRow("系统", "${contextBreakdown.systemPct.toInt()}%")
                    StatsRow("用户", "${contextBreakdown.userPct.toInt()}%")
                    StatsRow("助手", "${contextBreakdown.assistantPct.toInt()}%")
                    StatsRow("工具调用", "${contextBreakdown.toolPct.toInt()}%")
                }

                HorizontalDivider()

                // Time info
                StatsSection(title = "时间") {
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    StatsRow("创建时间", stats.createdAt.format(formatter))
                    StatsRow("最后活动", stats.updatedAt.format(formatter))
                }

                // Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        content()
    }
}

@Composable
private fun StatsRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ContextBreakdownBar(breakdown: ContextBreakdown) {
    val total = (breakdown.systemPct + breakdown.userPct + breakdown.assistantPct + breakdown.toolPct)
    if (total <= 0f) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(4.dp)
            )
    ) {
        if (breakdown.systemPct > 0) {
            Box(
                modifier = Modifier
                    .weight(breakdown.systemPct)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.tertiary)
            )
        }
        if (breakdown.userPct > 0) {
            Box(
                modifier = Modifier
                    .weight(breakdown.userPct)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        if (breakdown.assistantPct > 0) {
            Box(
                modifier = Modifier
                    .weight(breakdown.assistantPct)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary)
            )
        }
        if (breakdown.toolPct > 0) {
            Box(
                modifier = Modifier
                    .weight(breakdown.toolPct)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            )
        }
    }
}

private fun formatTokenCount(count: Int): String {
    return when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }
}
