package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.data.model.ToolResult

/**
 * Manages the markup elements used in conversations with the AI assistant.
 *
 * This class handles the generation of standardized XML-formatted status messages, tool invocation
 * formats, and tool results to be displayed in the conversation.
 */
class ConversationMarkupManager {

    companion object {
        private const val TOOL_RESULT_TRUNCATION_SUFFIX =
            "\n[工具结果过长，已截断。如需查看完整内容，请使用 read_file 工具读取原文件，或使用 grep_code / grep_context 搜索关键内容。]"

        /**
         * Creates an 'error' status markup element for a tool.
         *
         * @param toolName The name of the tool that produced the error
         * @param errorMessage The error message
         * @return The formatted status element
         */
        fun createToolErrorStatus(toolName: String, errorMessage: String): String {
            return createToolResultXml(
                toolName = toolName,
                status = "error",
                content = "<content><error>${errorMessage}</error></content>"
            )
        }

        /**
         * Creates a 'warning' status markup element.
         *
         * @param warningMessage The warning message to display
         * @return The formatted status element
         */
        fun createWarningStatus(warningMessage: String): String {
            return "<status type=\"warning\">$warningMessage</status>"
        }


        /**
         * Formats a tool result message for sending to the AI.
         *
         * @param result The tool execution result
         * @return The formatted tool result message
         */
        fun formatToolResultForMessage(result: ToolResult): String {
            return if (result.success) {
                createBoundedToolResultXml(
                    toolName = result.toolName,
                    status = "success",
                    rawPayload = result.result.toString()
                ) { payload ->
                    "<content>$payload</content>"
                }
            } else {
                createBoundedToolResultXml(
                    toolName = result.toolName,
                    status = "error",
                    rawPayload = result.error ?: "Unknown error"
                ) { payload ->
                    "<content><error>$payload</error></content>"
                }
            }
        }

        fun buildBoundedToolResultMessage(results: List<ToolResult>): String {
            if (results.isEmpty()) {
                return ""
            }

            val maxChars = ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS
            val separator = "\n"
            val builder = StringBuilder()

            for (result in results) {
                val formatted = formatToolResultForMessage(result)
                val additionalLength =
                    (if (builder.isEmpty()) 0 else separator.length) + formatted.length
                if (builder.length + additionalLength > maxChars) {
                    break
                }
                if (builder.isNotEmpty()) {
                    builder.append(separator)
                }
                builder.append(formatted)
            }

            return builder.toString()
        }

        /**
         * Formats a message indicating multiple tool invocations were found but only one will be
         * processed.
         *
         * @param context The context to access string resources
         * @param toolName The name of the tool that will be processed
         * @return The formatted warning message
         */
        fun createMultipleToolsWarning(context: Context, toolName: String): String {
            return createWarningStatus(
                    context.getString(R.string.conversation_markup_multiple_tools_warning, toolName)
            )
        }

        /**
         * Creates a message for when a tool is not available.
         *
         * @param toolName The name of the unavailable tool
         * @param details Optional detailed error message
         * @return The formatted error message
         */
        fun createToolNotAvailableError(toolName: String, details: String? = null): String {
            val errorMessage = details ?: "The tool `$toolName` is not available."
            return createToolErrorStatus(toolName, errorMessage)
        }

        private fun createToolResultXml(toolName: String, status: String, content: String): String {
            val tagName = ChatMarkupRegex.generateRandomToolResultTagName()
            return """<$tagName name="$toolName" status="$status">$content</$tagName>""".trimIndent()
        }

        private fun createBoundedToolResultXml(
            toolName: String,
            status: String,
            rawPayload: String,
            bodyBuilder: (String) -> String
        ): String {
            val emptyXml =
                createToolResultXml(
                    toolName = toolName,
                    status = status,
                    content = bodyBuilder("")
                )
            val maxPayloadChars =
                (ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS - emptyXml.length)
                    .coerceAtLeast(0)
            val boundedPayload = truncatePayload(rawPayload, maxPayloadChars)
            return createToolResultXml(
                toolName = toolName,
                status = status,
                content = bodyBuilder(boundedPayload)
            )
        }

        private fun truncatePayload(payload: String, maxChars: Int): String {
            // 先按行数截断
            val lineLimited = limitLines(payload)
            // 再按字符数截断
            if (lineLimited.length <= maxChars) {
                return lineLimited
            }
            if (maxChars <= 0) {
                return ""
            }
            if (TOOL_RESULT_TRUNCATION_SUFFIX.length >= maxChars) {
                return TOOL_RESULT_TRUNCATION_SUFFIX.take(maxChars)
            }
            return lineLimited
                .take(maxChars - TOOL_RESULT_TRUNCATION_SUFFIX.length)
                .trimEnd() + TOOL_RESULT_TRUNCATION_SUFFIX
        }

        /**
         * 限制工具结果的行数，超出部分截断并附加提示。
         * 保留头部行作为预览，尾部附加截断提示。
         */
        private fun limitLines(payload: String): String {
            val lines = payload.lines()
            if (lines.size <= ToolExecutionLimits.MAX_TOOL_RESULT_LINES) {
                return payload
            }
            val keptLines = lines.take(ToolExecutionLimits.MAX_TOOL_RESULT_LINES)
            val omittedCount = lines.size - ToolExecutionLimits.MAX_TOOL_RESULT_LINES
            return keptLines.joinToString("\n") +
                "\n...(${omittedCount} 行已截断)..." +
                TOOL_RESULT_TRUNCATION_SUFFIX
        }

    }
}
