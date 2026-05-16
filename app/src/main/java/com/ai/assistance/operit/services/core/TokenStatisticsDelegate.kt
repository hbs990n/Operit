package com.ai.assistance.operit.services.core

import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.api.chat.EnhancedAIService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** 委托类，负责管理token统计相关功能 */
class TokenStatisticsDelegate(
    private val coroutineScope: CoroutineScope,
    private val getEnhancedAiService: () -> EnhancedAIService?
) {
    companion object {
        private const val TAG = "TokenStatisticsDelegate"
    }

    /** 综合Token统计数据类 */
    data class ChatTokenStats(
        val inputTokens: Int,
        val outputTokens: Int,
        val windowSize: Int,
        val cachedInputTokens: Int = 0,
        val reasoningTokens: Int = 0,
        val apiCallCount: Int = 0,
        val provider: String = "",
        val modelName: String = "",
        val contextLimit: Int = 0
    )

    // --- UI State Flows ---
    private val _cumulativeInputTokens = MutableStateFlow(0)
    val cumulativeInputTokensFlow: StateFlow<Int> = _cumulativeInputTokens.asStateFlow()

    private val _cumulativeOutputTokens = MutableStateFlow(0)
    val cumulativeOutputTokensFlow: StateFlow<Int> = _cumulativeOutputTokens.asStateFlow()

    private val _currentWindowSize = MutableStateFlow(0)
    val currentWindowSizeFlow: StateFlow<Int> = _currentWindowSize.asStateFlow()

    private val _perRequestTokenCount = MutableStateFlow<Pair<Int, Int>?>(null)
    val perRequestTokenCountFlow: StateFlow<Pair<Int, Int>?> = _perRequestTokenCount.asStateFlow()

    private val _cumulativeCachedInputTokens = MutableStateFlow(0)
    val cumulativeCachedInputTokensFlow: StateFlow<Int> = _cumulativeCachedInputTokens.asStateFlow()

    private val _cumulativeReasoningTokens = MutableStateFlow(0)
    val cumulativeReasoningTokensFlow: StateFlow<Int> = _cumulativeReasoningTokens.asStateFlow()

    private val _apiCallCount = MutableStateFlow(0)
    val apiCallCountFlow: StateFlow<Int> = _apiCallCount.asStateFlow()

    // --- Internal State ---
    private var lastCurrentWindowSize = 0
    private var tokenCollectorJob: Job? = null

    private val tokenCollectorJobsByChatKey = ConcurrentHashMap<String, Job>()
    private val boundServicesByChatKey = ConcurrentHashMap<String, EnhancedAIService>()

    private val cumulativeInputTokensByChatKey = ConcurrentHashMap<String, Int>()
    private val cumulativeOutputTokensByChatKey = ConcurrentHashMap<String, Int>()
    private val lastWindowSizeByChatKey = ConcurrentHashMap<String, Int>()
    private val perRequestTokenCountByChatKey =
        ConcurrentHashMap<String, Pair<Int, Int>?>()
    private val cumulativeCachedInputTokensByChatKey = ConcurrentHashMap<String, Int>()
    private val cumulativeReasoningTokensByChatKey = ConcurrentHashMap<String, Int>()
    private val apiCallCountByChatKey = ConcurrentHashMap<String, Int>()

    @Volatile private var activeChatId: String? = null

    private fun chatKey(chatId: String?): String = chatId ?: "__DEFAULT_CHAT__"

    private fun isActiveKey(key: String): Boolean = key == chatKey(activeChatId)

    private fun refreshActiveFromCache() {
        val key = chatKey(activeChatId)
        val input = cumulativeInputTokensByChatKey[key] ?: 0
        val output = cumulativeOutputTokensByChatKey[key] ?: 0
        val window = lastWindowSizeByChatKey[key] ?: 0
        val perRequest = perRequestTokenCountByChatKey[key]
        val cachedInput = cumulativeCachedInputTokensByChatKey[key] ?: 0
        val reasoning = cumulativeReasoningTokensByChatKey[key] ?: 0
        val apiCalls = apiCallCountByChatKey[key] ?: 0

        _cumulativeInputTokens.value = input
        _cumulativeOutputTokens.value = output
        _currentWindowSize.value = window
        _perRequestTokenCount.value = perRequest
        _cumulativeCachedInputTokens.value = cachedInput
        _cumulativeReasoningTokens.value = reasoning
        _apiCallCount.value = apiCalls
        lastCurrentWindowSize = window
    }

    private fun handlePerRequestCounts(
        key: String,
        counts: Pair<Int, Int>?
    ) {
        if (counts == null) {
            perRequestTokenCountByChatKey.remove(key)
        } else {
            perRequestTokenCountByChatKey[key] = counts
        }

        if (isActiveKey(key)) {
            _perRequestTokenCount.value = counts
        }
    }

    private fun handleRequestWindowEstimate(
        key: String,
        windowSize: Int?
    ) {
        if (windowSize == null) {
            return
        }

        lastWindowSizeByChatKey[key] = windowSize

        if (isActiveKey(key)) {
            _currentWindowSize.value = windowSize
            lastCurrentWindowSize = windowSize
        }
    }

    fun setupCollectors() {
        tokenCollectorJob?.cancel() // Cancel previous collector if any
        val service = getEnhancedAiService() ?: return // Service not ready
        tokenCollectorJob = coroutineScope.launch(Dispatchers.IO) {
            launch {
                service.perRequestTokenCounts.collect { counts ->
                    handlePerRequestCounts(
                        key = chatKey(null),
                        counts = counts
                    )
                }
            }
            launch {
                service.requestWindowEstimateFlow.collect { windowSize ->
                    handleRequestWindowEstimate(
                        key = chatKey(null),
                        windowSize = windowSize
                    )
                }
            }
        }
    }

    fun setActiveChatId(chatId: String?) {
        activeChatId = chatId
        refreshActiveFromCache()
    }

    fun bindChatService(chatId: String?, service: EnhancedAIService) {
        val key = chatKey(chatId)
        boundServicesByChatKey[key] = service

        tokenCollectorJobsByChatKey[key]?.cancel()
        tokenCollectorJobsByChatKey[key] =
            coroutineScope.launch(Dispatchers.IO) {
                launch {
                    service.perRequestTokenCounts.collect { counts ->
                        handlePerRequestCounts(
                            key = key,
                            counts = counts
                        )
                    }
                }
                launch {
                    service.requestWindowEstimateFlow.collect { windowSize ->
                        handleRequestWindowEstimate(
                            key = key,
                            windowSize = windowSize
                        )
                    }
                }
            }

        if (isActiveKey(key)) {
            refreshActiveFromCache()
        }
    }

    /** 重置token统计 */
    fun resetTokenStatistics() {
        _cumulativeInputTokens.value = 0
        _cumulativeOutputTokens.value = 0
        _currentWindowSize.value = 0
        _perRequestTokenCount.value = null
        _cumulativeCachedInputTokens.value = 0
        _cumulativeReasoningTokens.value = 0
        _apiCallCount.value = 0
        lastCurrentWindowSize = 0

        cumulativeInputTokensByChatKey.clear()
        cumulativeOutputTokensByChatKey.clear()
        lastWindowSizeByChatKey.clear()
        perRequestTokenCountByChatKey.clear()
        cumulativeCachedInputTokensByChatKey.clear()
        cumulativeReasoningTokensByChatKey.clear()
        apiCallCountByChatKey.clear()

        // 同时重置服务中的token计数
        val services = buildSet {
            getEnhancedAiService()?.let { add(it) }
            addAll(boundServicesByChatKey.values)
        }
        services.forEach { it.resetTokenCounters() }
        AppLogger.d(TAG, "token统计已重置")
    }

    /** 更新累计的token统计信息 */
    fun updateCumulativeStatistics(chatId: String? = activeChatId, serviceOverride: EnhancedAIService? = null) {
        val key = chatKey(chatId)
        val service = serviceOverride ?: boundServicesByChatKey[key] ?: getEnhancedAiService()
        service?.let {
            try {
                // 从AI服务获取最新的token统计
                val currentInputTokens = it.getCurrentInputTokenCount()
                val currentOutputTokens = it.getCurrentOutputTokenCount()
                val currentCachedInput = it.cachedInputTokenCount
                val currentReasoning = it.getCurrentReasoningTokenCount()

                // 更新累计token数
                val newInput = (cumulativeInputTokensByChatKey[key] ?: 0) + currentInputTokens
                val newOutput = (cumulativeOutputTokensByChatKey[key] ?: 0) + currentOutputTokens
                cumulativeInputTokensByChatKey[key] = newInput
                cumulativeOutputTokensByChatKey[key] = newOutput
                cumulativeCachedInputTokensByChatKey[key] = currentCachedInput
                cumulativeReasoningTokensByChatKey[key] = currentReasoning

                // 增加API调用计数
                val newApiCallCount = (apiCallCountByChatKey[key] ?: 0) + 1
                apiCallCountByChatKey[key] = newApiCallCount

                if (isActiveKey(key)) {
                    _cumulativeInputTokens.value = newInput
                    _cumulativeOutputTokens.value = newOutput
                    _cumulativeCachedInputTokens.value = currentCachedInput
                    _cumulativeReasoningTokens.value = currentReasoning
                    _apiCallCount.value = newApiCallCount
                }

                AppLogger.d(
                        TAG,
                    "Cumulative token stats updated - " +
                            "Input: $newInput, Output: $newOutput, CachedInput: $currentCachedInput, Reasoning: $currentReasoning, ApiCalls: $newApiCallCount"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取累计token计数时出错: ${e.message}", e)
            }
        }
    }

    /** 设置累计token计数 */
    fun setTokenCounts(chatId: String?, inputTokens: Int, outputTokens: Int, windowSize: Int) {
        val key = chatKey(chatId)
        cumulativeInputTokensByChatKey[key] = inputTokens
        cumulativeOutputTokensByChatKey[key] = outputTokens
        lastWindowSizeByChatKey[key] = windowSize

        if (isActiveKey(key)) {
            _cumulativeInputTokens.value = inputTokens
            _cumulativeOutputTokens.value = outputTokens
            _currentWindowSize.value = windowSize
            lastCurrentWindowSize = windowSize
        }
    }

    fun setTokenCounts(inputTokens: Int, outputTokens: Int, windowSize: Int) {
        setTokenCounts(activeChatId, inputTokens, outputTokens, windowSize)
    }

    /** 获取当前累计token计数 */
    fun getCumulativeTokenCounts(chatId: String? = activeChatId): Pair<Int, Int> {
        val key = chatKey(chatId)
        return Pair(
            cumulativeInputTokensByChatKey[key] ?: 0,
            cumulativeOutputTokensByChatKey[key] ?: 0
        )
    }

    /** 获取最近一次的实际上下文窗口大小 */
    fun getLastCurrentWindowSize(chatId: String? = activeChatId): Int {
        val key = chatKey(chatId)
        return lastWindowSizeByChatKey[key] ?: 0
    }

    /** 获取综合Token统计数据 */
    fun getChatTokenStats(chatId: String? = activeChatId): ChatTokenStats {
        val key = chatKey(chatId)
        return ChatTokenStats(
            inputTokens = cumulativeInputTokensByChatKey[key] ?: 0,
            outputTokens = cumulativeOutputTokensByChatKey[key] ?: 0,
            windowSize = lastWindowSizeByChatKey[key] ?: 0,
            cachedInputTokens = cumulativeCachedInputTokensByChatKey[key] ?: 0,
            reasoningTokens = cumulativeReasoningTokensByChatKey[key] ?: 0,
            apiCallCount = apiCallCountByChatKey[key] ?: 0
        )
    }
}
