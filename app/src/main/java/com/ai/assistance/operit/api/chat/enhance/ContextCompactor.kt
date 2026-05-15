package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.util.AppLogger

/**
 * 上下文压缩器：当对话 token 接近上下文窗口上限时，将旧消息压缩为摘要，
 * 释放上下文空间让 Agent 继续工作。
 *
 * 核心策略（参照 OpenCode 的 compaction 机制，适配 Operit）：
 * - 当 currentTokens / maxTokens >= COMPACTION_THRESHOLD 时触发
 * - 将历史分为"待压缩部分"和"保留部分"（保留最近 N 轮完整工具循环）
 * - 调用 ConversationService.generateSummaryFromPromptTurns() 生成摘要
 * - 用 [SYSTEM, SUMMARY(摘要), ...保留部分] 替换原始历史
 * - SUMMARY 类型在 OpenAIProvider/ClaudeProvider 中均映射为 "user" role
 *
 * 注意：每次用户对话最多执行一次 compaction，避免循环压缩。
 */
object ContextCompactor {

    private const val TAG = "ContextCompactor"

    /**
     * 判断是否需要压缩，如果需要则执行压缩并返回新的历史。
     *
     * @param history 当前对话历史
     * @param currentTokens 当前 token 用量
     * @param maxTokens 上下文窗口大小
     * @param conversationService 用于生成摘要的 ConversationService 实例
     * @param multiServiceManager 用于获取 SUMMARY 服务实例
     * @return 压缩后的新历史，如果不需要压缩则返回 null
     */
    suspend fun compactIfNeeded(
        history: List<PromptTurn>,
        currentTokens: Int,
        maxTokens: Int,
        conversationService: ConversationService,
        multiServiceManager: MultiServiceManager
    ): List<PromptTurn>? {
        if (maxTokens <= 0) return null

        val usageRatio = currentTokens.toDouble() / maxTokens.toDouble()
        if (usageRatio < ToolExecutionLimits.COMPACTION_THRESHOLD) {
            AppLogger.d(TAG, "Token 使用率 $usageRatio 未达压缩阈值 ${ToolExecutionLimits.COMPACTION_THRESHOLD}，跳过压缩")
            return null
        }

        AppLogger.d(TAG, "Token 使用率 $usageRatio 达到压缩阈值 ${ToolExecutionLimits.COMPACTION_THRESHOLD}，开始压缩")

        // 分割历史：找到保留部分的起始位置
        val splitIndex = findSplitIndex(history)

        if (splitIndex <= 1) {
            AppLogger.d(TAG, "可压缩的消息太少（splitIndex=$splitIndex），跳过压缩")
            return null
        }

        val toCompact = history.subList(0, splitIndex)
        val toPreserve = history.subList(splitIndex, history.size)

        // 检查待压缩部分是否包含非系统消息（不能只压缩一条 SYSTEM）
        val hasUserContent = toCompact.any {
            it.kind == PromptTurnKind.USER || it.kind == PromptTurnKind.ASSISTANT
        }
        if (!hasUserContent) {
            AppLogger.d(TAG, "待压缩部分无用户/助手消息，跳过压缩")
            return null
        }

        // 调用 ConversationService 生成摘要
        val summary: String
        try {
            // 检查现有历史中是否已有 SUMMARY 消息（上一次的摘要），作为 previousSummary 传入
            val previousSummary = history
                .filter { it.kind == PromptTurnKind.SUMMARY }
                .lastOrNull()
                ?.content

            summary = conversationService.generateSummaryFromPromptTurns(
                messages = toCompact,
                previousSummary = previousSummary,
                multiServiceManager = multiServiceManager
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成压缩摘要失败，跳过压缩", e)
            return null
        }

        if (summary.isBlank()) {
            AppLogger.w(TAG, "压缩摘要为空，跳过压缩")
            return null
        }

        // 构建压缩后的历史：SYSTEM + SUMMARY + 保留部分
        // 保留原始 SYSTEM 消息（第一条），插入 SUMMARY，再接保留部分
        val systemTurn = history.firstOrNull { it.kind == PromptTurnKind.SYSTEM }
        val compactedHistory = mutableListOf<PromptTurn>()

        // 保留 SYSTEM
        if (systemTurn != null) {
            compactedHistory.add(systemTurn)
        }

        // 添加 SUMMARY
        compactedHistory.add(
            PromptTurn(
                kind = PromptTurnKind.SUMMARY,
                content = buildCompactionSummaryContent(summary)
            )
        )

        // 添加保留部分（跳过其中的 SYSTEM 消息，因为已经添加了）
        toPreserve.forEach { turn ->
            if (turn.kind != PromptTurnKind.SYSTEM) {
                compactedHistory.add(turn)
            }
        }

        val originalChars = history.sumOf { it.content.length }
        val compactedChars = compactedHistory.sumOf { it.content.length }
        AppLogger.d(
            TAG,
            "压缩完成: ${history.size} 条消息 → ${compactedHistory.size} 条, " +
                "字符数 ${originalChars} → ${compactedChars}, " +
                "压缩部分 ${splitIndex} 条, 保留部分 ${toPreserve.size} 条"
        )

        return compactedHistory
    }

    /**
     * 找到历史分割点：保留最近 N 轮完整的工具循环。
     *
     * "一轮完整工具循环" = ASSISTANT + TOOL_CALL + TOOL_RESULT
     * 从后向前扫描，找到第 N 轮的起始位置。
     */
    private fun findSplitIndex(history: List<PromptTurn>): Int {
        val protectRounds = ToolExecutionLimits.COMPACTION_PROTECT_RECENT_ROUNDS

        // 从后向前找 ASSISTANT 消息作为"轮"的起点
        var roundsFound = 0
        var splitIndex = history.size

        for (i in history.lastIndex downTo 0) {
            if (history[i].kind == PromptTurnKind.ASSISTANT) {
                roundsFound++
                if (roundsFound >= protectRounds) {
                    splitIndex = i
                    break
                }
            }
        }

        // 如果没找到足够的轮次，至少保留第一条 SYSTEM
        if (splitIndex <= 0) {
            splitIndex = if (history.isNotEmpty() && history[0].kind == PromptTurnKind.SYSTEM) 1 else 0
        }

        return splitIndex
    }

    /**
     * 构建压缩摘要的内容，添加标记前缀让模型理解这是压缩后的上下文。
     */
    private fun buildCompactionSummaryContent(summary: String): String {
        return "[上下文压缩摘要 - 以下是之前对话的摘要，请基于此继续工作]\n\n$summary"
    }
}
