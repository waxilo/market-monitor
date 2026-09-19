package com.waxilo.marketmonitor.ui.webhook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.domain.repository.WebhookRepository
import com.waxilo.marketmonitor.domain.webhook.WebhookEndpoint
import com.waxilo.marketmonitor.domain.webhook.WebhookTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 端点编辑器里的临时表单；为空模板表示使用内置 payload。 */
data class WebhookDraft(
    val id: Long = 0L,
    val name: String = "",
    val url: String = "",
    val template: String = "",
    val error: String? = null,
) {
    val isNew: Boolean get() = id == 0L
}

data class WebhookUiState(
    val endpoints: List<WebhookEndpoint> = emptyList(),
    val globalEnabled: Boolean = true,
    val allowInsecure: Boolean = false,
    val ruleCount: Int = 0,
    val draft: WebhookDraft? = null,
    val busy: Boolean = false,
    /** 一次性的操作结果（测试发送的成功提示），消费后即清空。 */
    val notice: String? = null,
)

/**
 * Webhook 端点管理（PRD FR-4.1 / FR-4.3）。
 *
 * 测试发送用一条固定的示例事件，因此不需要等真实预警命中就能验证对端是否收得到，
 * 也不会把用户手滑填错的模板留到半夜才暴露。
 */
class WebhookViewModel(container: AppContainer) : ViewModel() {

    private val webhooks: WebhookRepository = container.webhookRepository
    private val settings = container.settings
    private val sender = container.webhookSender

    private val draft = MutableStateFlow<WebhookDraft?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    val state: StateFlow<WebhookUiState> = combine(
        webhooks.endpoints(),
        settings.settings,
        combine(draft, notice, busy) { d, n, b -> Triple(d, n, b) },
        container.alertRepository.rules(),
    ) { endpoints, snapshot, flags, rules ->
        WebhookUiState(
            endpoints = endpoints,
            globalEnabled = snapshot.webhookEnabled,
            allowInsecure = snapshot.allowInsecureWebhook,
            ruleCount = rules.count { it.enabled },
            draft = flags.first,
            busy = flags.third,
            notice = flags.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebhookUiState())

    fun openNew() = openEditor(null)

    fun openEditor(endpoint: WebhookEndpoint?) {
        draft.value = endpoint?.toDraft() ?: WebhookDraft()
    }

    fun dismissEditor() {
        draft.value = null
    }

    fun onDraft(change: (WebhookDraft) -> WebhookDraft) {
        draft.value = draft.value?.let(change)
    }

    fun consumeNotice() {
        notice.value = null
    }

    fun setGlobalEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.edit { it.copy(webhookEnabled = enabled) } }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { webhooks.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            webhooks.delete(id)
            notice.value = "端点已删除"
        }
    }

    /** 校验通过才落库；模板错误必须在配置页就能看到，而不是等触发时静默失败。 */
    fun saveDraft() {
        val current = draft.value ?: return
        val problem = WebhookTemplate.validateName(current.name)
            ?: WebhookTemplate.validateUrl(current.url, state.value.allowInsecure)
            ?: current.template.trim().takeIf { it.isNotEmpty() }
                ?.let { WebhookTemplate.validateTemplate(it, demoEvent()) }
        if (problem != null) {
            draft.value = current.copy(error = problem)
            return
        }
        viewModelScope.launch {
            webhooks.save(current.toEndpoint())
            draft.value = null
            notice.value = "已保存"
        }
    }

    fun testDraft() {
        val current = draft.value ?: return
        val url = current.url.trim()
        val urlProblem = WebhookTemplate.validateUrl(url, state.value.allowInsecure)
        val payload = current.template.trim().ifEmpty { WebhookTemplate.DEFAULT_PAYLOAD }
        val templateProblem = WebhookTemplate.validateTemplate(payload, demoEvent())
        if (urlProblem != null || templateProblem != null) {
            draft.value = current.copy(error = urlProblem ?: templateProblem)
            return
        }
        send(url, WebhookTemplate.render(payload, demoEvent()).text, "测试推送已送达")
    }

    /** 对已保存端点直接试一次，省去重新填模板。 */
    fun testExisting(endpoint: WebhookEndpoint) {
        val problem = WebhookTemplate.validateUrl(endpoint.url, state.value.allowInsecure)
        if (problem != null) {
            notice.value = problem
            return
        }
        send(endpoint.url, WebhookTemplate.render(endpoint.effectiveTemplate, demoEvent()).text, "「${endpoint.name}」测试已送达")
    }

    private fun send(url: String, payload: String, successText: String) {
        viewModelScope.launch {
            busy.value = true
            val failure = runCatching { sender.post(url, payload) }
                .getOrElse { "发送失败：${it.javaClass.simpleName}" }
            busy.value = false
            notice.value = failure ?: successText
        }
    }

    private fun WebhookDraft.toEndpoint(): WebhookEndpoint = WebhookEndpoint(
        id = id,
        name = name.trim(),
        url = url.trim(),
        enabled = true,
        template = template.trim(),
    )

    private fun WebhookEndpoint.toDraft(): WebhookDraft = WebhookDraft(
        id = id,
        name = name,
        url = url,
        template = template,
    )

    private fun demoEvent(): AlertEvent = WebhookTemplate.demoEvent()
}
