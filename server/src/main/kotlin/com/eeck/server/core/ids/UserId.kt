package com.eeck.server.core.ids

/** Zero-cost wrapper — kills the "passed a userId where a chatId was expected" class of bug. */
@JvmInline
value class UserId(val value: String)
