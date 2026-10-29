package kmp.bootstrap.appkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kmp.bootstrap.appkit.Navigator

/*
 * Nối [Navigator] (ngăn xếp điều hướng, không dính Compose) vào màn hình:
 *
 *     val nav = remember { Navigator<Route>(Route.Home) }
 *     NavHost(nav) { route ->                         // vẽ route hiện tại; nút lùi Android tự lùi ngăn xếp
 *         when (route) {
 *             Route.Home -> HomeScreen(onOpen = { nav.push(Route.Detail(it)) })
 *             is Route.Detail -> DetailScreen(route.id)
 *         }
 *     }
 *
 * Nút lùi: Android → lùi một màn, hết ngăn xếp thì để hệ điều hành thoát app. iOS không có nút lùi hệ thống (dùng nút ‹ của AppHeader).
 */
@Composable
fun <R : Any> NavHost(navigator: Navigator<R>, content: @Composable (R) -> Unit) {
    val stack by navigator.stack.collectAsState()
    BackHandler(enabled = stack.size > 1) { navigator.back() }
    content(stack.last())
}

/** Bắt nút lùi của hệ điều hành khi [enabled]. Android cài thật; iOS và desktop không làm gì. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)
