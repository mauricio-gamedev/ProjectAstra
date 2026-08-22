package io.github.astromg01.launcher.account

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AccountStore(application)

    var accounts by mutableStateOf(store.load())
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        errorMessage = null
    }

    fun addOffline(username: String): Boolean {
        val result = OfflineAccountService.create(username)
        val account = result.getOrElse {
            errorMessage = it.message ?: "Não foi possível criar a conta offline."
            return false
        }

        if (accounts.any { it.id == account.id }) {
            errorMessage = "Essa conta offline já existe."
            return false
        }

        val normalized = if (accounts.isEmpty()) account.copy(isDefault = true) else account
        accounts = accounts + normalized
        store.save(accounts)
        errorMessage = null
        return true
    }

    fun setDefault(id: String) {
        accounts = accounts.map { it.copy(isDefault = it.id == id) }
        store.save(accounts)
    }

    fun remove(id: String) {
        val removedWasDefault = accounts.firstOrNull { it.id == id }?.isDefault == true
        var newAccounts = accounts.filterNot { it.id == id }
        if (removedWasDefault && newAccounts.isNotEmpty()) {
            newAccounts = newAccounts.mapIndexed { index, account ->
                account.copy(isDefault = index == 0)
            }
        }
        accounts = newAccounts
        store.save(accounts)
    }
}
