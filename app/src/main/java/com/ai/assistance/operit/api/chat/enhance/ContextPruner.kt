package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.util.AppLogger

/**
 * 裁剪对话历史中旧的工具结果，以降低 Token 消耗。
 *
 * 核心策略：
 * - 只替换 TOOL_RESULT 类型消息的 content，不删除消息，不动 ASSISTANT 消息（保护 reasoning_content）
 * - 倒序扫描，保护最近 [PRUNE_PROTECT_RECENT_ROUNDS] 轮工具结果不被裁剪
 * - 对话中工具结果总字符数超过 [PRUNE_MINIMUM_TOTAL_CHARS] 才触发裁剪
 *
 * 参照 OpenCode 的 prune 机制，适配 Operit 的 XML 内部协议。
 */
object ContextPruner {

    private const val TAG = "ContextPruner"

    /** 裁剪占位符，替换旧工具结果的正文 */
    private const val PRUNE_PLACEHOLDER = "[旧工具结果内容已清除]"

    /**
     * 裁剪对话历史中的旧工具结果。
     *
     * @param history 当前对话历史
     * @return 裁剪后的对话历史（只替换了旧 TOOL_RESULT 的 content）
     */
    fun pruneOldToolResults(history: List<PromptTurn>): List<PromptTurn> {
        // 收集所有 TOOL_RESULT 的索引
        val toolResultIndices = history.indices.filter { history[it].kind == PromptTurnKind.TOOL_RESULT }

        if (toolResultIndices.isEmpty()) {
            return history
        }

        // 计算工具结果总字符数
        val totalToolResultChars = toolResultIndices.sumOf { history[it].content.length }

        if (totalToolResultChars < ToolExecutionLimits.PRUNE_MINIMUM_TOTAL_CHARS) {
            AppLogger.d(TAG, "工具结果总字符数($totalToolResultChars)未达裁剪阈值(${ToolExecutionLimits.PRUNE_MINIMUM_TOTAL_CHARS})，跳过裁剪")
            return history
        }

        // 保护最近 N 轮工具结果
        val protectCount = ToolExecutionLimits.PRUNE_PROTECT_RECENT_ROUNDS
        val protectedIndices = if (toolResultIndices.size <= protectCount) {
            toolResultIndices.toSet()
        } else {
            toolResultIndices.takeLast(protectCount).toSet()
        }

        // 执行裁剪
        var prunedCount = 0
        var savedChars = 0
        val result = history.mapIndexed { index, turn ->
            if (turn.kind == PromptTurnKind.TOOL_RESULT && index !in protectedIndices) {
                val originalLength = turn.content.length
                val prunedContent = buildPrunedContent(turn)
                if (prunedContent != turn.content) {
                    prunedCount++
                    savedChars += originalLength - prunedContent.length
                }
                turn.copy(content = prunedContent)
            } else {
                turn
            }
        }

        if (prunedCount > 0) {
            AppLogger.d(TAG, "裁剪完成: 裁剪了 $prunedCount 条旧工具结果，节省约 $savedChars 字符")
        }

        return result
    }

    /**
     * 构建裁剪后的工具结果内容。
     *
     * 保留 XML 标签结构（tool_result 标签），但将正文替换为占位符。
     * 这样做是为了：
     * 1. 保持 API 消息结构合法（tool_calls 后必须有 tool_result）
     * 2. 保持 tool_call_id 配对关系
     * 3. 模型仍然知道自己调用过什么工具
     */
    private fun buildPrunedContent(turn: PromptTurn): String {
        val original = turn.content

        // 尝试提取 XML 标签内的正文内容
        val xmlContentPattern = Regex("""<content>([\s\S]*)</content>""")
        val match = xmlContentPattern.find(original)

        if (match != null) {
            // 有 <content> 标签，只替换标签内的内容
            val prunedInner = "<content>$PRUNE_PLACEHOLDER</content>"
            return xmlContentPattern.replace(original, prunedInner)
        }

        // 没有标准 XML 结构，整条替换为简短占位
        // 但保留工具名信息（如果有）
        val toolName = turn.toolName
        return if (toolName != null) {
            "${PRUNE_PLACEHOLDER}(工具: $toolName)"
        } else {
            PRUNE_PLACEHOLDER
        }
    }
}
