package com.waxilo.marketmonitor.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlin.reflect.KClass

/**
 * 手工容器注入 ViewModel（PRD 7 已定：不引入 Hilt）。
 * 只提供一个入口，创建逻辑仍由各 ViewModel 的构造参数决定。
 */
class ContainerViewModelFactory(
    private val container: AppContainer,
    private val create: (AppContainer) -> ViewModel,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: KClass<T>): T = create(container) as T
}
