package io.github.astromg01.launcher.account

enum class AccountType {
    MICROSOFT,
    OFFLINE
}

data class AccountProfile(
    val id: String,
    val username: String,
    val uuid: String,
    val type: AccountType,
    val isDefault: Boolean = false
)
