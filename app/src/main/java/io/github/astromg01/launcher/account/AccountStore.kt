package io.github.astromg01.launcher.account

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    fun load(): List<AccountProfile> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    add(
                        AccountProfile(
                            id = obj.getString("id"),
                            username = obj.getString("username"),
                            uuid = obj.getString("uuid"),
                            type = AccountType.valueOf(obj.getString("type")),
                            isDefault = obj.optBoolean("isDefault", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(accounts: List<AccountProfile>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject().apply {
                    put("id", account.id)
                    put("username", account.username)
                    put("uuid", account.uuid)
                    put("type", account.type.name)
                    put("isDefault", account.isDefault)
                }
            )
        }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    companion object {
        private const val KEY_ACCOUNTS = "account_list_v1"
    }
}
