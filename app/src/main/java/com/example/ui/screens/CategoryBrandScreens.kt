package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.BrandEntity
import com.example.data.local.entities.CategoryEntity
import com.example.data.local.entities.SubcategoryEntity
import com.example.ui.components.AppTopBar
import com.example.ui.viewmodels.BrandViewModel
import com.example.ui.viewmodels.CategoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryListScreen(
    viewModel: CategoryViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showAddSubcategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var newCategoryDesc by remember { mutableStateOf("") }
    var newSubcategoryName by remember { mutableStateOf("") }

    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var editingSubcategory by remember { mutableStateOf<SubcategoryEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    editingCategory?.let { cat ->
        NameEditDialog(
            title = "Edit category",
            initialName = cat.name,
            initialExtra = cat.description,
            extraLabel = "Description",
            removeLabel = "Delete (hidden instead if it has products)",
            onDismiss = { editingCategory = null },
            onSave = { name, desc -> viewModel.updateCategory(cat, name, desc) { editingCategory = null } },
            onRemove = { viewModel.removeCategory(cat) { editingCategory = null } }
        )
    }

    editingSubcategory?.let { sub ->
        NameEditDialog(
            title = "Edit subcategory",
            initialName = sub.name,
            initialExtra = null,
            extraLabel = null,
            removeLabel = "Delete (hidden instead if it has products)",
            onDismiss = { editingSubcategory = null },
            onSave = { name, _ -> viewModel.updateSubcategory(sub, name) { editingSubcategory = null } },
            onRemove = { viewModel.removeSubcategory(sub) { editingSubcategory = null } }
        )
    }

    if (showAddCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("Add New Category", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = { Text("Category Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_category_name_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newCategoryDesc,
                        onValueChange = { newCategoryDesc = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCategoryName.isNotBlank()) {
                            viewModel.saveCategory(newCategoryName, newCategoryDesc) {
                                newCategoryName = ""
                                newCategoryDesc = ""
                                showAddCategoryDialog = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("save_category_dialog_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddCategoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddSubcategoryDialog && uiState.selectedCategory != null) {
        AlertDialog(
            onDismissRequest = { showAddSubcategoryDialog = false },
            title = { Text("Add Subcategory to ${uiState.selectedCategory!!.name}", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newSubcategoryName,
                    onValueChange = { newSubcategoryName = it },
                    label = { Text("Subcategory Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newSubcategoryName.isNotBlank()) {
                            viewModel.saveSubcategory(uiState.selectedCategory!!.id, newSubcategoryName, "") {
                                newSubcategoryName = ""
                                showAddSubcategoryDialog = false
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddSubcategoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "Categories",
                subtitle = "${uiState.categories.size} categories active",
                onBackClick = onNavigateBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddCategoryDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_category_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Category")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(uiState.categories, key = { it.id }) { cat ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectCategory(cat) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.selectedCategory?.id == cat.id) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cat.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { editingCategory = cat }, modifier = Modifier.testTag("edit_category_${cat.id}")) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit ${cat.name}", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (cat.description.isNotBlank()) {
                            Text(
                                text = cat.description,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        // Subcategories expansion
                        if (uiState.selectedCategory?.id == cat.id) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Subcategories (${uiState.subcategories.size})",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                TextButton(onClick = { showAddSubcategoryDialog = true }) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add Subcategory")
                                }
                            }
                            if (uiState.subcategories.isEmpty()) {
                                Text(
                                    text = "No subcategories yet.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    uiState.subcategories.forEach { sub ->
                                        SuggestionChip(
                                            onClick = { editingSubcategory = sub },
                                            label = { Text(sub.name) },
                                            icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandListScreen(
    viewModel: BrandViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddBrandDialog by remember { mutableStateOf(false) }
    var newBrandName by remember { mutableStateOf("") }
    var newBrandOrigin by remember { mutableStateOf("") }
    var editingBrand by remember { mutableStateOf<BrandEntity?>(null) }

    editingBrand?.let { brand ->
        NameEditDialog(
            title = "Edit brand",
            initialName = brand.name,
            initialExtra = brand.originCountry,
            extraLabel = "Origin / Notes",
            removeLabel = "Delete (hidden instead if it has products)",
            onDismiss = { editingBrand = null },
            onSave = { name, origin -> viewModel.updateBrand(brand, name, origin) { editingBrand = null } },
            onRemove = { viewModel.removeBrand(brand) { editingBrand = null } }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    if (showAddBrandDialog) {
        AlertDialog(
            onDismissRequest = { showAddBrandDialog = false },
            title = { Text("Add Brand", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newBrandName,
                        onValueChange = { newBrandName = it },
                        label = { Text("Brand Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_brand_name_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newBrandOrigin,
                        onValueChange = { newBrandOrigin = it },
                        label = { Text("Origin / Notes (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newBrandName.isNotBlank()) {
                            viewModel.saveBrand(newBrandName, newBrandOrigin) {
                                newBrandName = ""
                                newBrandOrigin = ""
                                showAddBrandDialog = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("save_brand_dialog_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddBrandDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "Brands",
                subtitle = "${uiState.brands.size} brands active",
                onBackClick = onNavigateBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddBrandDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_brand_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Brand")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(uiState.brands, key = { it.id }) { brand ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = brand.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            if (brand.originCountry.isNotBlank()) {
                                Text(
                                    text = "Origin: ${brand.originCountry}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                        IconButton(onClick = { editingBrand = brand }, modifier = Modifier.testTag("edit_brand_${brand.id}")) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit ${brand.name}", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/** Rename dialog shared by categories, subcategories and brands. */
@Composable
private fun NameEditDialog(
    title: String,
    initialName: String,
    initialExtra: String?,
    extraLabel: String?,
    removeLabel: String,
    onDismiss: () -> Unit,
    onSave: (name: String, extra: String) -> Unit,
    onRemove: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var extra by remember { mutableStateOf(initialExtra ?: "") }
    var confirmRemove by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (extraLabel != null) {
                    OutlinedTextField(extra, { extra = it }, label = { Text(extraLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (confirmRemove) {
                    Text("Sure? Tap again to confirm.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { if (confirmRemove) onRemove() else confirmRemove = true }) {
                    Text(removeLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onSave(name, extra) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
