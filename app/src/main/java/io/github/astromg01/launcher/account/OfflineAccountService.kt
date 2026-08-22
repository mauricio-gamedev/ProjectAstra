package io.github.astromg01.launcher.account

import java.nio.charset.StandardCharsets
import java.util.UUID

object OfflineAccountService {
    private val validUsername = Regex("^[A-Za-z0-9_]{3,16}$")

    fun validateUsername(username: String): Result<String> {
        val clean = username.trim()
        return if (validUsername.matches(clean)) {
            Result.success(clean)
        } else {
            Result.failure(
                IllegalArgumentException(
                    "Use 3–16 caracteres: letras, números ou _."
                )
            )
        }
    }

    /**
     * Stable UUID compatible with the conventional Minecraft offline identity scheme:
     * UUID.nameUUIDFromBytes("OfflinePlayer:<username>").
     */
    fun create(username: String): Result<AccountProfile> = validateUsername(username).map { clean ->
        val uuid = UUID.nameUUIDFromBytes(
            "OfflinePlayer:$clean".toByteArray(StandardCharsets.UTF_8)
        ).toString()

        AccountProfile(
            id = "offline:$uuid",
            username = clean,
            uuid = uuid,
            type = AccountType.OFFLINE
        )
    }
}
