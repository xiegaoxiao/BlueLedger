package com.blueledger.app.data.local

import androidx.room.TypeConverter
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.TransactionType

/**
 * 枚举与基础类型转换器。
 *
 * 日期与时间戳**不**走这里：occurredOn 用 epochDay Long、审计时间用 epochMillis Long，
 * 由实体显式声明，避免“同一个值两种存储形式”的歧义。
 */
class LedgerConverters {

    @TypeConverter
    fun toTransactionType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toAccountKind(value: String): AccountKind = AccountKind.valueOf(value)

    @TypeConverter
    fun fromAccountKind(value: AccountKind): String = value.name
}
