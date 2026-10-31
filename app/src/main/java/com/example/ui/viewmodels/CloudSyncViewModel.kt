package com.example.ui.viewmodels

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sync.ShopDevice
import com.example.sync.ShopMember
import com.example.sync.ShopRef
import com.example.sync.ShopRoles
import com.example.sync.SyncConflictEntity
import com.example.sync.SyncManager
import com.example.sync.SyncStatus
import com.example.sync.SyncUser
import com.example.system.GoogleSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudUiState(
    /** What is running right now ("Putting the shop online…"); null when idle. */
    val busy: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
    /** Shops of the signed-in account; null while not looked up yet. */
    val shops: List<ShopRef>? = null,
    val mayCreateShops: Boolean = false,
    val members: List<ShopMember> = emptyList(),
    val devices: List<ShopDevice> = emptyList(),
    val loadingLists: Boolean = false
)

class CloudSyncViewModel(private val sync: SyncManager) : ViewModel() {
    val status: StateFlow<SyncStatus> = sync.status
    val user: StateFlow<SyncUser?> = sync.user
    val issues: Flow<List<SyncConflictEntity>> = sync.issues
    val available: Boolean get() = sync.available

    private val _state = MutableStateFlow(CloudUiState())
    val state: StateFlow<CloudUiState> = _state.asStateFlow()

    init {
        // Look things up again whenever the account or the linked shop changes
        viewModelScope.launch {
            combine(sync.user, sync.status) { u, s -> Triple(u?.email, s.shopId, s.role) }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    fun refresh() {
        if (!sync.available || sync.user.value == null) {
            _state.update { it.copy(shops = null, members = emptyList(), devices = emptyList(), mayCreateShops = false) }
            return
        }
        val linked = sync.status.value.isLinked
        viewModelScope.launch {
            _state.update { it.copy(loadingLists = true) }
            try {
                if (!linked) {
                    val shops = sync.findMyShops()
                    val mayCreate = runCatching { sync.mayCreateShops() }.getOrDefault(false)
                    _state.update { it.copy(shops = shops, mayCreateShops = mayCreate, members = emptyList(), devices = emptyList()) }
                } else {
                    val members = if (ShopRoles.canManageMembers(sync.status.value.role)) sync.members() else emptyList()
                    val devices = sync.devices()
                    _state.update { it.copy(members = members, devices = devices, shops = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = SyncManager.friendly(e), isError = true) }
            } finally {
                _state.update { it.copy(loadingLists = false) }
            }
        }
    }

    fun signIn(activity: Activity) = run("Signing in…") {
        try {
            sync.signInWithGoogle(GoogleSignIn.idToken(activity))
        } catch (e: GoogleSignIn.Cancelled) {
            // The account picker was closed
        }
    }

    fun signInForTests(email: String) = run("Signing in…") { sync.signInForTests(email) }

    fun signOut() {
        sync.signOut()
        _state.update { CloudUiState() }
    }

    fun putShopOnline() = run("Putting the shop online…", done = "The shop is online. Everything on this phone is being uploaded.") {
        sync.putShopOnline()
    }

    fun join(shop: ShopRef) = run("Joining ${shop.name}…") { sync.joinShop(shop) }

    fun unlink() = run("Unlinking…", done = "This phone is no longer linked. Its records stay here.") { sync.unlink() }

    fun syncNow() {
        sync.syncNow()
        refresh()
    }

    fun addMember(email: String, role: String) = run("Adding $email…", done = "$email was added. They sign in with that Google account.") {
        sync.addMember(email, role)
        refreshNow()
    }

    fun changeRole(member: ShopMember, role: String) = run("Saving…") {
        sync.changeMemberRole(member.email, role)
        refreshNow()
    }

    fun setActive(member: ShopMember, active: Boolean) = run("Saving…") {
        sync.setMemberActive(member.email, active)
        refreshNow()
    }

    fun dismissIssue(issue: SyncConflictEntity) {
        viewModelScope.launch { sync.dismissIssue(issue.id) }
    }

    fun clearMessage() = _state.update { it.copy(message = null, isError = false) }

    private suspend fun refreshNow() {
        val members = sync.members()
        val devices = sync.devices()
        _state.update { it.copy(members = members, devices = devices) }
    }

    private fun run(busy: String, done: String? = null, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = busy, message = null, isError = false) }
            try {
                block()
                _state.update { it.copy(busy = null, message = done, isError = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, message = SyncManager.friendly(e), isError = true) }
            }
        }
    }
}
