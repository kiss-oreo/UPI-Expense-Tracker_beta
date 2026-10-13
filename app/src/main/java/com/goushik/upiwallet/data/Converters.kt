package com.goushik.upiwallet.data

import androidx.room.TypeConverter

/** Stores the enums as their stable `name` so raw SQL (e.g. status != 'DISCARDED') stays readable. */
class Converters {
    @TypeConverter fun fromDirection(d: Direction): String = d.name
    @TypeConverter fun toDirection(s: String): Direction = Direction.valueOf(s)

    @TypeConverter fun fromStatus(s: TxnStatus): String = s.name
    @TypeConverter fun toStatus(s: String): TxnStatus = TxnStatus.valueOf(s)

    @TypeConverter fun fromKind(kind: TxnKind): String = kind.name
    @TypeConverter fun toKind(value: String): TxnKind = TxnKind.valueOf(value)

    @TypeConverter fun fromAccountType(type: AccountType): String = type.name
    @TypeConverter fun toAccountType(value: String): AccountType = AccountType.valueOf(value)
}
