package com.blueledger.app.data.repository

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 稳定 ID 生成器。
 *
 * 生产使用随机 UUID；测试注入可预测的序列，便于断言。
 * ID 一旦写入就不得改变（备份与历史账单都按 ID 关联）。
 */
fun interface LedgerIdGenerator {
    fun newId(): String
}

class UuidLedgerIdGenerator : LedgerIdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** 测试用：`prefix-1`、`prefix-2`…… */
class SequentialLedgerIdGenerator(private val prefix: String = "id") : LedgerIdGenerator {
    private val counter = AtomicLong(0L)

    override fun newId(): String = "$prefix-${counter.incrementAndGet()}"
}
