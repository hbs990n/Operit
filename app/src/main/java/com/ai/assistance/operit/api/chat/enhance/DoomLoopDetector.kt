package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.util.AppLogger

/**
 * 死循环检测器：检测 Agent 循环中重复相同的工具调用。
 *
 * 核心策略（参照 OpenCode 的 doom loop detection，适配 Operit）：
 * - 维护最近工具调用的滑动窗口（工具名 + 参数摘要）
 * - 连续相同调用达到 DOOM_LOOP_THRESHOLD - 1 次时返回 WARNING（注入警告让 AI 自纠）
 * - 连续相同调用达到 DOOM_LOOP_THRESHOLD 次时返回 STOP（强制终止循环）
 *
 * 生命周期：每个 MessageExecutionContext 创建一个实例，与单次用户对话一致。
 */
class DoomLoopDetector {

    private val tag = "DoomLoopDetector"

    enum class DoomLoopResult {
        /** 正常，无重复 */
        OK,
        /** 连续重复接近阈值，注入警告让 AI 自我纠正 */
        WARNING,
        /** 连续重复达到阈值，必须强制终止 */
        STOP
    }

    /** 最近工具调用的指纹列表（工具名 + 参数前缀） */
    private val recentCallFingerprints = mutableListOf<String>()

    /** 当前连续重复计数 */
    private var consecutiveRepeatCount = 0

    /** 上一次工具调用的指纹 */
    private var lastFingerprint: String = ""

    /**
     * 检查当前工具调用是否构成 doom loop。
     *
     * @param toolName 工具名称
     * @param toolInput 工具参数/输入内容
     * @return 检测结果
     */
    fun check(toolName: String, toolInput: String): DoomLoopResult {
        val fingerprint = buildFingerprint(toolName, toolInput)

        if (fingerprint == lastFingerprint) {
            consecutiveRepeatCount++
        } else {
            consecutiveRepeatCount = 1
            lastFingerprint = fingerprint
        }

        // 保持滑动窗口
        recentCallFingerprints.add(fingerprint)
        if (recentCallFingerprints.size > ToolExecutionLimits.DOOM_LOOP_THRESHOLD + 1) {
            recentCallFingerprints.removeAt(0)
        }

        val threshold = ToolExecutionLimits.DOOM_LOOP_THRESHOLD
        val result = when {
            consecutiveRepeatCount >= threshold -> {
                AppLogger.w(tag, "检测到 Doom Loop: 工具=$toolName, 连续重复=$consecutiveRepeatCount 次, 强制终止")
                DoomLoopResult.STOP
            }
            consecutiveRepeatCount >= threshold - 1 -> {
                AppLogger.w(tag, "检测到可能 Doom Loop: 工具=$toolName, 连续重复=$consecutiveRepeatCount 次, 注入警告")
                DoomLoopResult.WARNING
            }
            else -> DoomLoopResult.OK
        }

        return result
    }

    /**
     * 构建工具调用的指纹，用于判断两次调用是否"相同"。
     * 取工具名 + 参数前 500 字符的哈希值作为指纹。
     */
    private fun buildFingerprint(toolName: String, toolInput: String): String {
        val inputDigest = toolInput.take(500)
        return "$toolName|$inputDigest"
    }
}
