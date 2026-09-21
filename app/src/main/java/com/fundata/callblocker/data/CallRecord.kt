package com.fundata.callblocker.data

data class CallRecord(
    val id: Long,
    val number: String,
    val preferredDisplayName: String?,
    val cachedName: String?,
    val timestamp: Long,
    val type: Int = 0
)
