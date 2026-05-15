package com.ai.assistance.operit.core.tools

object ToolExecutionLimits {
    const val MAX_FILE_READ_BYTES = 32_000
    const val DEFAULT_FILE_READ_PART_LINES = 200
    const val MAX_TEXT_RESULT_LENGTH = 5_000

    /** 单条工具结果消息的最大字符数（从 64000 降至 8000，大幅减少 Token 消耗） */
    const val MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS = 8_000

    /** 单条工具结果的最大行数，超出部分截断 */
    const val MAX_TOOL_RESULT_LINES = 200

    /** 旧工具结果裁剪：保护最近 N 轮工具结果不被裁剪 */
    const val PRUNE_PROTECT_RECENT_ROUNDS = 2

    /** 旧工具结果裁剪：对话中工具结果总字符数超过此阈值才触发裁剪 */
    const val PRUNE_MINIMUM_TOTAL_CHARS = 10_000
}
