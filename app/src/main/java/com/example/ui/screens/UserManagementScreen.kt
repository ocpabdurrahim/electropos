package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.security.UserRoles
import com.example.data.local.entities.UserEntity
import com.example.data.repository.AuthRepository
import com.example.ui.components.AppTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UserManagementViewModel(private val authRepository: AuthRepository) : ViewModel() {
    val users: StateFlow<List<UserEntity>> =
        authRepository.allUsers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val currentUser = authRepository.currentUser

    fun createUser(name: String, username: String, role: String, pin: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(authRepository.createUser(name, username, role, pin).exceptionOrNull()?.message)
        }
    }

    fun updateUser(user: UserEntity, name: String, role: String, isActive: Boolean, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(authRepository.updateUser(user.id, name, role, isActive).exceptionOrNull()?.message)
        }
    }

    fun resetPin(user: UserEntity, pin: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(authRepository.resetUserPin(user.id, pin).exceptionOrNull()?.message)
        }
    }
}

@Composable
fun UserManagementScreen(
    viewModel: UserManagementViewModel,
    onNavigateBack: () -> Unit
) {
    val users by viewModel.users.collectAsState()
    val me by viewModel.currentUser.collectAsState()
    var editing by remember { mutableStateOf<UserEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            AppTopBar(title = "Users & Roles", subtitle = "Who can sign in and what they can do", onBackClick = onNavigateBack)
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Add user") },
                modifier = Modifier.testTag("add_user_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            message?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text(msg, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    UserRoles.ALL.forEach { role ->
                        Text(
                            "${UserRoles.label(role)}: ${UserRoles.description(role)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(users, key = { it.id }) { user ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { editing = user },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (user.isActive) Icons.Default.Person else Icons.Default.PersonOff,
                            contentDescription = null,
                            tint = if (user.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.name + if (user.id == me?.id) "  (you)" else "", fontWeight = FontWeight.Bold)
                            Text("@${user.username} • ${UserRoles.label(user.role)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!user.isActive) {
                            Text("Inactive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        UserFormDialog(
            title = "Add user",
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, username, role, _, pin, setError ->
                viewModel.createUser(name, username, role, pin) { err ->
                    if (err == null) {
                        creating = false
                        message = "User @${username.trim().lowercase()} added."
                    } else setError(err)
                }
            }
        )
    }

    editing?.let { user ->
        UserFormDialog(
            title = "Edit ${user.name}",
            initial = user,
            onDismiss = { editing = null },
            onSave = { name, _, role, active, pin, setError ->
                viewModel.updateUser(user, name, role, active) { err ->
                    if (err != null) {
                        setError(err)
                    } else if (pin.isNotBlank()) {
                        viewModel.resetPin(user, pin) { pinErr ->
                            if (pinErr == null) {
                                editing = null
                                message = "Saved. New PIN set for @${user.username}."
                            } else setError(pinErr)
                        }
                    } else {
                        editing = null
                        message = "Saved @${user.username}."
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserFormDialog(
    title: String,
    initial: UserEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, username: String, role: String, isActive: Boolean, pin: String, setError: (String) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var role by remember { mutableStateOf(initial?.role ?: UserRoles.SALESPERSON) }
    var active by remember { mutableStateOf(initial?.isActive ?: true) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var roleMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = initial == null,
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(expanded = roleMenu, onExpandedChange = { roleMenu = it }) {
                    OutlinedTextField(
                        value = UserRoles.label(role),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Role") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                        UserRoles.ALL.forEach { r ->
                            DropdownMenuItem(text = { Text(UserRoles.label(r)) }, onClick = { role = r; roleMenu = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) pin = it; error = null },
                    label = { Text(if (initial == null) "PIN (4-12 digits)" else "New PIN (leave empty to keep)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (initial != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = active, onCheckedChange = { active = it })
                        Spacer(Modifier.width(8.dp))
                        Text(if (active) "Can sign in" else "Blocked from signing in")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, username, role, active, pin) { error = it } }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
