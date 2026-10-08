package com.blueledger.app.core.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** 暂停读订阅时保留页面状态与正在执行的保存操作。 */
interface ScreenSubscriptionOwner {
    fun setSubscriptionsActive(active: Boolean)
}

/** 每次激活只创建一组读订阅；后台页面不继续响应数据库失效通知。 */
class ScreenSubscriptions(private val parentScope: CoroutineScope) {
    private var readScope: CoroutineScope? = null

    fun setActive(active: Boolean, subscribe: (CoroutineScope) -> Unit) {
        if (!active) {
            readScope?.cancel()
            readScope = null
        } else if (readScope == null) {
            val scope = CoroutineScope(
                parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
            )
            readScope = scope
            subscribe(scope)
        }
    }
}

/** 跟随导航条目的 STARTED 状态，也处理后台、返回和保存后的导航恢复。 */
@Composable
fun ObserveScreenSubscriptions(owner: ScreenSubscriptionOwner) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, owner) {
        val observer = LifecycleEventObserver { _, _ ->
            owner.setSubscriptionsActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        owner.setSubscriptionsActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            owner.setSubscriptionsActive(false)
        }
    }
}
