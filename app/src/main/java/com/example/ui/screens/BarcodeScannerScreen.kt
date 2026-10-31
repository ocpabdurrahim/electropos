package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.repository.ProductRepository
import com.example.ui.components.AppTopBar
import com.example.ui.theme.AlertRose
import com.example.ui.theme.PrimaryCyan
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera + manual barcode entry.
 *
 * Lookup mode ([onCodeCaptured] == null): the code is resolved to a product (barcode, SKU, IMEI or serial)
 * and [onProductFound] opens it; unknown codes offer [onCreateProductWithBarcode].
 * Capture mode ([onCodeCaptured] != null): the raw code is handed straight back to the screen that asked
 * for it (POS cart, purchase entry, product form).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScannerScreen(
    productRepository: ProductRepository,
    onNavigateBack: () -> Unit,
    onProductFound: (Long) -> Unit,
    onCreateProductWithBarcode: (String) -> Unit,
    onCodeCaptured: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    var manualBarcode by remember { mutableStateOf("") }
    var notFoundBarcode by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    // The camera stops reporting codes after one is handled until the user asks to scan again
    val cameraPaused = remember { AtomicBoolean(false) }
    var showScanAgain by remember { mutableStateOf(false) }

    fun handleCode(code: String) {
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) return
        if (onCodeCaptured != null) {
            onCodeCaptured(cleanCode)
            return
        }
        coroutineScope.launch {
            isSearching = true
            notFoundBarcode = null
            val match = productRepository.findByCode(cleanCode)
            isSearching = false
            if (match != null) {
                onProductFound(match.product.id)
            } else {
                notFoundBarcode = cleanCode
                showScanAgain = true
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Barcode Scanner",
                subtitle = "Scan or enter barcode / SKU / IMEI",
                onBackClick = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Camera Viewfinder Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (!hasCameraPermission) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Camera Permission Required",
                            style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Grant camera permission to automatically scan barcodes through your lens.",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.8f))
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant Permission")
                        }
                    }
                } else {
                    CameraBarcodePreview(
                        paused = cameraPaused,
                        onBarcode = { code ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            cameraPaused.set(true)
                            handleCode(code)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    // Scanning Viewfinder Overlay
                    Box(
                        modifier = Modifier
                            .size(220.dp, 120.dp)
                            .border(2.dp, PrimaryCyan, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = "Align barcode within frame",
                            style = MaterialTheme.typography.labelSmall.copy(color = PrimaryCyan),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }

            if (hasCameraPermission && showScanAgain) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    notFoundBarcode = null
                    showScanAgain = false
                    cameraPaused.set(false)
                }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan again")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Not Found Banner & Action
            if (notFoundBarcode != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = AlertRose)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Barcode not found in catalog",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            )
                        }
                        Text(
                            text = "No product matches '$notFoundBarcode'. You can add it as a new product now.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onErrorContainer),
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                        Button(
                            onClick = { onCreateProductWithBarcode(notFoundBarcode!!) },
                            modifier = Modifier.fillMaxWidth().testTag("create_with_barcode_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Product with this Barcode")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Manual Barcode Input Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Manual Barcode Entry",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Or type barcode / SKU / IMEI using the keyboard:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualBarcode,
                            onValueChange = { manualBarcode = it },
                            placeholder = { Text("e.g. 195949038234") },
                            leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("manual_barcode_input")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { handleCode(manualBarcode) },
                            enabled = !isSearching && manualBarcode.isNotBlank(),
                            modifier = Modifier.height(56.dp).testTag("lookup_barcode_button")
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                            } else {
                                Text(if (onCodeCaptured != null) "Use" else "Search")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Live camera preview that reports the first barcode it reads (ML Kit, works offline). */
@Composable
private fun CameraBarcodePreview(
    paused: AtomicBoolean,
    onBarcode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnBarcode by rememberUpdatedState(onBarcode)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
        )
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val mediaImage = proxy.image
            if (mediaImage == null || paused.get()) {
                proxy.close()
                return@setAnalyzer
            }
            val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { codes ->
                    val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf { v -> v.isNotEmpty() } }
                    // Stays paused until the screen asks to scan again
                    if (value != null && paused.compareAndSet(false, true)) {
                        mainExecutor.execute { latestOnBarcode(value) }
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }

        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            runCatching {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, mainExecutor)

        onDispose {
            provider?.unbindAll()
            analysis.clearAnalyzer()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
