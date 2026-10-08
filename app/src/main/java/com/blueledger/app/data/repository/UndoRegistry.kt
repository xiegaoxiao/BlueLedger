package com.blueledger.app.data.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * 撤销凭据注册表（内存态，单进程有效）。
 *
 * 设计要点：
 * - 令牌与**本次删除事件**绑定：同一账单再次删除会产生新令牌，旧凭据立即失效。
 * - 成功撤销后条目被移除，同一凭据无法二次使用。
 * - 进程重启后注册表为空，旧凭据自然失效（撤销窗口只有 5 秒，重启后不提供撤销是正确行为）。
 * - 恢复全库时清空，避免旧账本的删除凭据在新账本上误恢复记录。
 */
internal class UndoRegistry {

    data class Entry(
        val token: String,
        val deletedAtEpochMillis: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(transactionId: String, token: String, deletedAtEpochMillis: Long) {
        entries[transactionId] = Entry(token = token, deletedAtEpochMillis = deletedAtEpochMillis)
    }

    /** 当前有效的删除事件；null 表示没有可撤销的删除（或已被消费）。 */
    fun current(transactionId: String): Entry? = entries[transactionId]

    /** 成功撤销后消费凭据。 */
    fun consume(transactionId: String) {
        entries.remove(transactionId)
    }

    fun clear() {
        entries.clear()
    }
}
