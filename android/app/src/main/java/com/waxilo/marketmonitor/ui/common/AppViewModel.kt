package com.waxilo.marketmonitor.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.waxilo.marketmonitor.appContainer
import com.waxilo.marketmonitor.di.AppContainer
import com.waxilo.marketmonitor.di.ContainerViewModelFactory

/** 从当前 Context 取容器并创建 ViewModel；同一屏幕类可用 [key] 区分实例。 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    noinline create: (AppContainer) -> VM,
): VM = viewModel(
    key = key,
    factory = ContainerViewModelFactory(LocalContext.current.appContainer(), create),
)
