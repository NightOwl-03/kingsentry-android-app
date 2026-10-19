package com.example.kingsentry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jan.supabase.auth.auth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.camera.core.Preview as CameraPreview
import androidx.compose.ui.unit.dp
import com.example.kingsentry.ui.theme.*
import com.example.kingsentry.models.AnalysisResult
import com.example.kingsentry.models.MockResults
import com.example.kingsentry.ui.Language
import com.example.kingsentry.ui.Localization
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import android.location.Location
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.kingsentry.ui.SplashScreen
import kotlinx.coroutines.delay
import com.example.kingsentry.models.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.kingsentry.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = KingSentryDatabase.getDatabase(applicationContext)
        val dao = database.kingSentryDao()

        com.example.kingsentry.data.sync.SyncStatusManager.init(applicationContext, dao)

        val supabaseUserId = com.example.kingsentry.data.remote.SupabaseClientModule.client.auth.currentUserOrNull()?.id
        android.util.Log.d("SupabaseAuth", "Supabase session: ${supabaseUserId ?: "NONE"}")

        com.example.kingsentry.workers.SyncWorker.enqueueOneTime(applicationContext, replaceExisting = false)
        com.example.kingsentry.workers.SyncWorker.enqueuePeriodic(applicationContext)
        setContent {
            KingSentryTheme {
                var showSplash by remember { mutableStateOf(true) }
                var currentLanguage by remember { mutableStateOf(Language.ENGLISH) }
                val strings = Localization[currentLanguage]!!

                if (showSplash) {
                    SplashScreen(strings = strings)
                    LaunchedEffect(Unit) {
                        delay(2000)
                        showSplash = false
                    }
                } else {
                    MainScreen(
                        initialLanguage = currentLanguage,
                        onLanguageChange = { currentLanguage = it }
                    )
                }
            }
        }
    }
}

sealed class Screen(val icon: ImageVector) {
    object Dashboard : Screen(Icons.Default.Home)
    object History : Screen(Icons.AutoMirrored.Filled.List)
    object Library : Screen(Icons.AutoMirrored.Filled.MenuBook)
    object Settings : Screen(Icons.Default.Settings)
    object Scanner : Screen(Icons.Default.CameraAlt)
    object OrchardSelection : Screen(Icons.Default.Place)
    object Auth : Screen(Icons.Default.Lock)
    data class Result(
        val analysis: AnalysisResult, 
        val orchardName: String = "Unknown Area", 
        val timestamp: Long = System.currentTimeMillis(),
        val capturedBitmap: Bitmap? = null,
        val imagePath: String? = null,
        val detections: List<DetectionResult> = emptyList(),
        val id: Int = 0 // Database ID for deletion reference
    ) : Screen(Icons.Default.CheckCircle)
    data class DiseaseDetail(val disease: AnalysisResult) : Screen(Icons.Default.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialLanguage: Language = Language.ENGLISH,
    onLanguageChange: (Language) -> Unit = {}
) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var currentLanguage by remember { mutableStateOf(initialLanguage) }
    var selectedOrchard by remember { mutableStateOf<Orchard?>(null) }
    val strings = Localization[currentLanguage]!!
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // Database initialization
    val database = remember { KingSentryDatabase.getDatabase(context) }
    val dao = database.kingSentryDao()
    
    val sessionManager = remember { SessionManager(context) }
    
    // Check session on start
    LaunchedEffect(Unit) {
        if (!sessionManager.isLoggedIn()) {
            currentScreen = Screen.Auth
        }
    }

    // Sync state observation and Toast transitions
    val syncState by com.example.kingsentry.data.sync.SyncStatusManager.uiState.collectAsState()
    var previousSyncState by remember { mutableStateOf<com.example.kingsentry.data.sync.SyncUiState?>(null) }

    LaunchedEffect(syncState) {
        val prev = previousSyncState
        if (prev is com.example.kingsentry.data.sync.SyncUiState.Syncing && syncState is com.example.kingsentry.data.sync.SyncUiState.Synced) {
            android.widget.Toast.makeText(context, strings.syncAllSynced, android.widget.Toast.LENGTH_SHORT).show()
        } else if (prev is com.example.kingsentry.data.sync.SyncUiState.Syncing && syncState is com.example.kingsentry.data.sync.SyncUiState.Failed) {
            android.widget.Toast.makeText(context, strings.syncFailedToast, android.widget.Toast.LENGTH_LONG).show()
        }
        previousSyncState = syncState
    }
    
    // Requirement: Initialize and manage YOLO lifecycle
    val yoloHelper = remember { YoloInferenceHelper(context) }
    DisposableEffect(Unit) {
        onDispose { yoloHelper.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 0.dp
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = strings.appTitle,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                val navigationItems = listOf(
                    Triple(Screen.Dashboard, strings.homeTab, Icons.Default.Home),
                    Triple(Screen.History, strings.historyTab, Icons.AutoMirrored.Filled.List),
                    Triple(Screen.Library, strings.diseaseLibrary, Icons.Default.MenuBook),
                    Triple(Screen.Settings, strings.settings, Icons.Default.Settings)
                )

                navigationItems.forEach { (screen, label, icon) ->
                    NavigationDrawerItem(
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                        selected = currentScreen == screen,
                        onClick = {
                            currentScreen = screen
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                // Log Out Option
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(22.dp)) },
                    label = { Text("Log Out", style = MaterialTheme.typography.labelLarge) },
                    selected = false,
                    onClick = {
                        sessionManager.clearSession()
                        currentScreen = Screen.Auth
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedTextColor = MaterialTheme.colorScheme.error,
                        unselectedIconColor = MaterialTheme.colorScheme.error
                    )
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                Column(modifier = Modifier.padding(28.dp)) {
                    Text(
                        text = "V1.0.4 • Build 2026",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(ForestGreen, RoundedCornerShape(3.dp)))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.offlineStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (currentScreen !is Screen.Scanner && currentScreen !is Screen.Auth) {
                    TopAppBar(
                        title = { Text(strings.appTitle) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            com.example.kingsentry.ui.SyncStatusChip(
                                state = syncState,
                                strings = strings,
                                onRetry = {
                                    com.example.kingsentry.data.sync.SyncStatusManager.clearFailure()
                                    com.example.kingsentry.workers.SyncWorker.enqueueOneTime(context, replaceExisting = true)
                                },
                                modifier = Modifier.padding(end = 4.dp)
                            )

                            if (currentScreen is Screen.History) {
                                var showClearAllDialog by remember { mutableStateOf(false) }
                                
                                IconButton(onClick = { showClearAllDialog = true }) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All")
                                }
                                
                                if (showClearAllDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showClearAllDialog = false },
                                        title = { Text("Clear All History?") },
                                        text = { Text("This will permanently delete all scan records and their photos. This action cannot be undone.") },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                scope.launch {
                                                    // Database Cleanup
                                                    dao.deleteAllScans()
                                                    showClearAllDialog = false
                                                }
                                            }) {
                                                Text("DELETE ALL", color = MaterialTheme.colorScheme.error)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showClearAllDialog = false }) {
                                                Text("CANCEL")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (currentScreen !is Screen.Scanner && currentScreen !is Screen.Auth) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp
                    ) {
                        val items = listOf(
                            Triple(Screen.Dashboard, strings.homeTab, Screen.Dashboard.icon),
                            Triple(Screen.History, strings.historyTab, Screen.History.icon),
                            Triple(Screen.Library, strings.diseaseLibrary, Screen.Library.icon)
                        )
                        items.forEach { (screen, label, icon) ->
                            NavigationBarItem(
                                selected = currentScreen == screen,
                                onClick = { currentScreen = screen },
                                icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp)) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                when (val screen = currentScreen) {
                    Screen.Dashboard -> DashboardScreen(
                        strings = strings,
                        dao = dao,
                        sessionManager = sessionManager,
                        onScanClick = { currentScreen = Screen.OrchardSelection }
                    )
                    Screen.History -> HistoryScreen(
                        strings = strings, 
                        dao = dao,
                        sessionManager = sessionManager,
                        onResultDetail = { currentScreen = it }
                    )
                    Screen.Settings -> SettingsScreen(
                        strings = strings,
                        currentLanguage = currentLanguage,
                        onLanguageChange = { 
                            currentLanguage = it
                            onLanguageChange(it)
                        }
                    )
                    Screen.OrchardSelection -> OrchardSelectionScreen(
                        strings = strings,
                        dao = dao,
                        onOrchardSelected = { 
                            selectedOrchard = it
                            currentScreen = Screen.Scanner
                        },
                        onBack = { currentScreen = Screen.Dashboard }
                    )
                    Screen.Auth -> AuthScreen(
                        strings = strings,
                        dao = dao,
                        sessionManager = sessionManager,
                        onSuccess = { currentScreen = Screen.Dashboard }
                    )
                    Screen.Scanner -> ScannerScreen(
                        strings = strings,
                        yoloHelper = yoloHelper,
                        dao = dao,
                        onResult = { result, bitmap, detections -> 
                            currentScreen = Screen.Result(
                                analysis = result, 
                                orchardName = selectedOrchard?.orchardName ?: "Unknown Area",
                                capturedBitmap = bitmap,
                                detections = detections
                            )
                        },
                        onBack = { currentScreen = Screen.Dashboard }
                    )
                    is Screen.Result -> ResultScreen(
                        strings = strings,
                        result = screen.analysis,
                        orchardName = screen.orchardName,
                        timestamp = screen.timestamp,
                        capturedBitmap = screen.capturedBitmap,
                        imagePath = screen.imagePath, // Pass the image path
                        detections = screen.detections,
                        isHistory = screen.id > 0,
                        onSave = { 
                            scope.launch {
                                try {
                                    val currentUserId = sessionManager.getCurrentUserId()
                                    val diseaseId = dao.getDiseaseIdByName(screen.analysis.diseaseName) ?: 1
                                    
                                    val imagePath = ImageHandler.saveBitmap(context, screen.capturedBitmap!!, screen.detections)
                                    val scan = ScanResultData(
                                        userIdFk = currentUserId,
                                        orchardIdFk = selectedOrchard?.id ?: 1,
                                        diseaseIdFk = diseaseId,
                                        confidenceScore = screen.analysis.confidence,
                                        imageUriPath = imagePath ?: ""
                                    )
                                    val result = dao.insertScan(scan)
                                    if (result > 0) {
                                        android.util.Log.d("Database", "Scan saved successfully with ID: $result")
                                        com.example.kingsentry.workers.SyncWorker.enqueueOneTime(context, replaceExisting = true)
                                    } else {
                                        android.util.Log.e("Database", "Failed to insert scan record")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("Database", "Error saving scan: ${e.message}", e)
                                }
                            }
                        },
                        onBack = { currentScreen = Screen.Dashboard }
                    )
                    Screen.Library -> DiseaseLibraryScreen(
                        strings = strings,
                        dao = dao,
                        onDiseaseClick = { currentScreen = Screen.DiseaseDetail(it) }
                    )
                    is Screen.DiseaseDetail -> DiseaseDetailScreen(
                        strings = strings,
                        disease = screen.disease,
                        onBack = { currentScreen = Screen.Library }
                    )
                }
            }
        }
    }
}

// --- Screens ---

@Composable
fun DashboardScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    dao: KingSentryDao,
    sessionManager: SessionManager,
    onScanClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title
        Text(
            text = strings.appTitle,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Main Action Button
        Button(
            onClick = onScanClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = strings.scanButton,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Dashboard Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val currentUserId = remember { sessionManager.getCurrentUserId() }
            val history by dao.getFullScanHistory(currentUserId).collectAsState(initial = emptyList())
            val totalScans = history.size
            val healthyScans = history.count { it.diseaseName.contains("Healthy", ignoreCase = true) }

            SummaryCard(strings.totalScans, totalScans.toString(), Modifier.weight(1f))
            SummaryCard(strings.healthyScans, healthyScans.toString(), Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Instruction Text
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = ForestGreen)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = strings.dashboardInstruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun OrchardSelectionScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    dao: KingSentryDao,
    onOrchardSelected: (Orchard) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val orchardAreas by dao.getAllOrchards().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var newOrchardName by remember { mutableStateOf("") }
    
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            val loc = getCurrentLocation(context)
            if (loc != null) {
                latitude = loc.latitude
                longitude = loc.longitude
                locationError = null
            } else {
                locationError = "Unable to get current location. Please make sure Location is turned on."
            }
        } else {
            locationError = "Location permission denied."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
            }
            Text(strings.selectOrchard, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = { 
                        latitude = null
                        longitude = null
                        locationError = null
                        showAddDialog = true 
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ForestGreen)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.addOrchard, style = MaterialTheme.typography.titleMedium)
                }
            }

            items(orchardAreas) { area ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOrchardSelected(area) },
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = ForestGreen)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(area.orchardName, style = MaterialTheme.typography.titleMedium)
                            if (area.latitude != 0.0) {
                                Text(
                                    "${area.latitude}, ${area.longitude}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Gray)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(strings.addOrchard) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newOrchardName,
                        onValueChange = { newOrchardName = it },
                        label = { Text(strings.orchardArea) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GET CURRENT LOCATION")
                    }
                    
                    if (latitude != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("GPS Location", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("Latitude: $latitude", style = MaterialTheme.typography.bodySmall)
                                Text("Longitude: $longitude", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    
                    if (locationError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(locationError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newOrchardName.isNotBlank()) {
                        scope.launch {
                            dao.insertOrchard(Orchard(
                                orchardName = newOrchardName, 
                                latitude = latitude,
                                longitude = longitude
                            ))
                            newOrchardName = ""
                            showAddDialog = false
                        }
                    }
                }) {
                    Text(strings.doneButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(strings.back)
                }
            }
        )
    }
}

/**
 * Robust offline GPS helper.
 */
private fun getCurrentLocation(context: android.content.Context): Location? {
    val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
    
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && 
        ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return null
    }

    // Try GPS first (most accurate, works offline)
    val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    if (gpsLocation != null) return gpsLocation
    
    // Fallback to Network provider (less accurate, but works indoors)
    return locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
}

@Composable
fun ScannerScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    yoloHelper: YoloInferenceHelper,
    dao: KingSentryDao,
    onResult: (AnalysisResult, Bitmap, List<DetectionResult>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var isAnalyzing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Requirement 1: Add ImageCapture use case
    val imageCapture = remember { ImageCapture.Builder().build() }

    // Simulated "Leaf Detected" state for silent teaching
    var isLeafAligned by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        isLeafAligned = true
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val executor = ContextCompat.getMainExecutor(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = CameraPreview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        try {
                            cameraProvider.unbindAll()
                            // Bind Preview and ImageCapture
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                        } catch (e: Exception) { e.printStackTrace() }
                    }, executor)
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Overlays
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = strings.scannerInstruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        textAlign = TextAlign.Center
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(300.dp)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Leaf Silhouette Guide (Silent Teacher)
                        Icon(
                            Icons.Default.Eco, // Representing a leaf
                            contentDescription = null,
                            modifier = Modifier.size(200.dp),
                            tint = Color.White.copy(alpha = 0.3f)
                        )

                        // Dynamic Border (Orange -> Green)
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                6.dp,
                                if (isLeafAligned) ForestGreen else SafetyOrange
                            ),
                            shape = RoundedCornerShape(32.dp)
                        ) {}
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Button(
                    onClick = { 
                        if (isLeafAligned && !isAnalyzing) {
                            isAnalyzing = true
                            
                            // Requirement 2: Capture image and process asynchronously
                            imageCapture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = image.toBitmap()
                                        image.close()
                                        
                                        scope.launch {
                                            val resultData = yoloHelper.detect(bitmap)
                                            
                                            // Map model output to AnalysisResult from DB
                                            val diseaseName = resultData?.detectedDisease ?: "Healthy Leaf"
                                            val fullDetail = dao.getFullDiseaseDetailByName(diseaseName)
                                            
                                            val analysis = if (fullDetail != null) {
                                                fullDetail.toAnalysisResult(resultData?.confidenceScore ?: 0f)
                                            } else {
                                                // Fallback if DB is missing records
                                                MockResults.healthy.copy(
                                                    confidence = resultData?.confidenceScore ?: 1.0f
                                                )
                                            }
                                            
                                            // Navigate with real data
                                            onResult(analysis, bitmap, resultData?.boundingBoxes ?: emptyList())
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        exception.printStackTrace()
                                        isAnalyzing = false
                                    }
                                }
                            )
                        }
                    },
                    enabled = isLeafAligned && !isAnalyzing,
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLeafAligned) ForestGreen else Color.Gray
                    )
                ) {
                    Text(strings.captureAnalyze, style = MaterialTheme.typography.titleLarge)
                }
            }

            if (isAnalyzing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ForestGreen, strokeWidth = 6.dp, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(strings.analyzing, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(strings.cameraPermission, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text(strings.grantPermission)
                }
            }
        }
    }
}

@Composable
fun ResultScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    result: AnalysisResult,
    orchardName: String,
    timestamp: Long,
    capturedBitmap: Bitmap? = null,
    imagePath: String? = null,
    detections: List<DetectionResult> = emptyList(),
    isHistory: Boolean = false,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var isSaved by remember { mutableStateOf(false) }
    val dateString = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    val timeString = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
            }
            Text(
                strings.scanResult,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Leaf Detection Image with Bounding Box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val displayBitmap = capturedBitmap ?: remember(imagePath) { ImageHandler.loadBitmap(imagePath) }
                
                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Mock Scanned Leaf Image
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(32.dp).alpha(0.5f),
                        tint = Color.Gray
                    )
                }
                
                // Real Bounding Boxes from Model
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 4.dp.toPx()
                    
                    // YOLO input was 640x640. Map coordinates to current canvas size.
                    detections.forEach { detection ->
                        val scaleX = size.width / 640f
                        val scaleY = size.height / 640f
                        
                        drawRect(
                            color = result.riskColor,
                            topLeft = androidx.compose.ui.geometry.Offset(
                                detection.boundingBox.left * scaleX, 
                                detection.boundingBox.top * scaleY
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                (detection.boundingBox.right - detection.boundingBox.left) * scaleX,
                                (detection.boundingBox.bottom - detection.boundingBox.top) * scaleY
                            ),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Metadata Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetadataRow(Icons.Default.Place, strings.orchardArea, orchardName)
                MetadataRow(Icons.Default.CalendarToday, "Date", dateString)
                MetadataRow(Icons.Default.AccessTime, "Time", timeString)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Result Card
        val isUnknown = result.diseaseName == "Unknown / Not a Durian Leaf"
        
        if (isUnknown) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Unknown / Not a Durian Leaf".uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This does not seem to be a durian leaf or the confidence is too low.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = result.riskColor.copy(alpha = 0.1f)),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (result.diseaseName.contains("Healthy", ignoreCase = true)) result.diseaseName.uppercase() else result.diseaseName.uppercase(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = result.riskColor,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RiskBadge(result.riskLevel, result.riskColor)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "${strings.confidence}: ${(result.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.Gray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Disease Info Card
        if (!isUnknown) {
            val isHealthy = result.diseaseName.contains("Healthy", ignoreCase = true)
            InfoSection(
                title = if (isHealthy) strings.description else strings.diseaseInfo,
                desc = result.description,
                symptoms = result.symptoms,
                causes = result.causes,
                prevention = result.prevention,
                strings = strings
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isHealthy) strings.maintenanceTips else strings.treatmentTitle,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            result.recommendationsDetail.forEach { recommendation ->
                TreatmentItem(recommendation)
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            if (result.referenceName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Sources & References",
                    style = MaterialTheme.typography.titleLarge,
                    color = ForestGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Disease Info: ${result.referenceName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (result.referenceUrl.isNotEmpty() && result.referenceUrl.startsWith("http")) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = result.referenceUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(result.referenceUrl))
                                        context.startActivity(intent)
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isUnknown && !isHistory) {
                OutlinedButton(
                    onClick = { 
                        if (!isSaved) {
                            onSave()
                            isSaved = true
                        }
                    },
                    modifier = Modifier.weight(1f).height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isSaved
                ) {
                    Text(if (isSaved) strings.scanHistory else strings.saveScan, style = MaterialTheme.typography.titleMedium)
                }
            }
            
            Button(
                onClick = onBack,
                modifier = Modifier.weight(if (isUnknown || isHistory) 1f else 1f).height(64.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (isHistory) strings.back else strings.doneButton, style = MaterialTheme.typography.titleMedium)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun MetadataRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = ForestGreen)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun InfoSection(
    title: String,
    desc: String,
    symptoms: List<String>,
    causes: List<String>,
    prevention: List<String>,
    strings: com.example.kingsentry.ui.AppStrings
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = ForestGreen, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium)
            
            if (symptoms.isNotEmpty()) {
                BulletSection(strings.symptoms, symptoms)
            }
            if (causes.isNotEmpty()) {
                BulletSection(strings.causes, causes)
            }
            if (prevention.isNotEmpty()) {
                BulletSection(strings.prevention, prevention)
            }
        }
    }
}

@Composable
fun BulletSection(title: String, items: List<String>) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ForestGreen)
    Spacer(modifier = Modifier.height(8.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "•",
                modifier = Modifier.width(24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = ForestGreen,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = item,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            )
        }
    }
}

@Composable
fun SettingsScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    currentLanguage: Language,
    onLanguageChange: (Language) -> Unit
) {
    var isDarkMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.settings,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Language Setting
        SettingCard(title = strings.language) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End
            ) {
                Language.entries.forEach { lang ->
                    FilterChip(
                        selected = currentLanguage == lang,
                        onClick = { onLanguageChange(lang) },
                        label = { Text(lang.label, maxLines = 1) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dark Mode Setting
        SettingCard(title = strings.darkMode) {
            Switch(checked = isDarkMode, onCheckedChange = { isDarkMode = it })
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear History
        Button(
            onClick = { /* TODO */ },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RiskHigh),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(strings.clearHistory, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "KING'S SENTRY v1.0.4\nHighly Optimized Offline Diagnostic",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = Color.Gray
        )
    }
}

@Composable
fun DiseaseLibraryScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    dao: KingSentryDao,
    onDiseaseClick: (AnalysisResult) -> Unit
) {
    val diseaseListState = dao.getAllDiseases().collectAsState(initial = emptyList())
    val diseaseList = diseaseListState.value
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = strings.diseaseLibrary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(diseaseList) { disease: DiseaseDictionary ->
                val riskColor = when (disease.riskIdFk) {
                    3 -> RiskHigh
                    2 -> RiskMedium
                    else -> RiskLow
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { 
                        scope.launch {
                            val fullDetail = dao.getFullDiseaseDetailById(disease.id)
                            if (fullDetail != null) {
                                onDiseaseClick(fullDetail.toAnalysisResult(1.0f))
                            }
                        }
                    },
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(riskColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Eco, contentDescription = null, tint = riskColor)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(disease.diseaseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun DiseaseDetailScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    disease: AnalysisResult,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
            }
            Text(
                text = disease.diseaseName, 
                style = MaterialTheme.typography.headlineSmall, 
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        InfoSection(
            title = if (disease.diseaseName.contains("Healthy", ignoreCase = true)) strings.description else strings.diseaseInfo,
            desc = disease.description,
            symptoms = disease.symptoms,
            causes = disease.causes,
            prevention = disease.prevention,
            strings = strings
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = if (disease.diseaseName.contains("Healthy", ignoreCase = true)) strings.maintenanceTips else strings.treatmentTitle,
            style = MaterialTheme.typography.titleLarge,
            color = ForestGreen,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        disease.recommendationsDetail.forEach { recommendation ->
            TreatmentItem(recommendation)
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (disease.referenceName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Sources & References",
                style = MaterialTheme.typography.titleLarge,
                color = ForestGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Disease Info: ${disease.referenceName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (disease.referenceUrl.isNotEmpty() && disease.referenceUrl.startsWith("http")) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = disease.referenceUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(disease.referenceUrl))
                                    context.startActivity(intent)
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        )
                    }
                }
            }
        }
        
        // Extra space at bottom to avoid navigation bar overlap
        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
fun HistoryScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    dao: KingSentryDao,
    sessionManager: SessionManager,
    onResultDetail: (Screen.Result) -> Unit
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    val currentUserId = remember { sessionManager.getCurrentUserId() }
    val historyData by dao.getFullScanHistory(currentUserId).collectAsState(initial = emptyList())

    val filteredHistory = historyData.filter { 
        it.diseaseName.contains(searchQuery, ignoreCase = true) || 
        it.orchardName.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            placeholder = { Text(strings.searchPlaceholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(strings.filterBy + ":", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
            FilterChip(selected = false, onClick = {}, label = { Text("Disease") })
            FilterChip(selected = false, onClick = {}, label = { Text("Risk") })
            FilterChip(selected = false, onClick = {}, label = { Text("Orchard") })
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(filteredHistory) { scan ->
                HistoryCard(
                    scan = scan, 
                    onClick = { detail ->
                        scope.launch {
                            val fullDetail = dao.getFullDiseaseDetailByName(detail.diseaseName)
                            val analysis = fullDetail?.toAnalysisResult(detail.confidenceScore) ?: MockResults.healthy.copy(confidence = detail.confidenceScore)
                            
                            onResultDetail(Screen.Result(
                                analysis = analysis,
                                orchardName = detail.orchardName,
                                timestamp = detail.timestamp,
                                capturedBitmap = null,
                                imagePath = detail.imagePath,
                                id = detail.scanId
                            ))
                        }
                    },
                    onDelete = {
                        scope.launch {
                            // File Cleanup
                            ImageHandler.deleteImageFile(scan.imagePath)
                            // Database Cleanup
                            dao.deleteScan(ScanResultData(
                                id = scan.scanId,
                                userIdFk = currentUserId,
                                orchardIdFk = 0,
                                diseaseIdFk = 0,
                                confidenceScore = 0f,
                                imageUriPath = ""
                            ))
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun HistoryCard(scan: ScanHistoryDetail, onClick: (ScanHistoryDetail) -> Unit, onDelete: () -> Unit) {
    val dateString = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(scan.timestamp))
    val timeString = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(scan.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onClick(scan) },
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        (if (scan.riskLevelName.contains("High", ignoreCase = true)) RiskHigh 
                        else if (scan.riskLevelName.contains("Medium", ignoreCase = true)) RiskMedium 
                        else RiskLow).copy(alpha = 0.1f), 
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = remember(scan.imagePath) { ImageHandler.loadBitmap(scan.imagePath) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Image, 
                        contentDescription = null, 
                        tint = if (scan.riskLevelName.contains("High", ignoreCase = true)) RiskHigh 
                               else if (scan.riskLevelName.contains("Medium", ignoreCase = true)) RiskMedium 
                               else RiskLow
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(scan.diseaseName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(scan.orchardName, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
                Text("$dateString • $timeString", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskBadge(
                        scan.riskLevelName, 
                        if (scan.riskLevelName.contains("High", ignoreCase = true)) RiskHigh 
                        else if (scan.riskLevelName.contains("Medium", ignoreCase = true)) RiskMedium 
                        else RiskLow
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${(scan.confidenceScore * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                }
            }
            
            // Single Delete Icon
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color.Gray)
            }
        }
    }
}

// --- Components ---

@Composable
fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier, isAlert: Boolean = false) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isAlert) RiskHigh.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = if (isAlert) RiskHigh else Color.Gray)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = if (isAlert) RiskHigh else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun RiskBadge(text: String, color: Color) {
    Surface(
        color = color,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White
        )
    }
}

@Composable
fun TreatmentItem(recommendation: TreatmentRecommendation) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = ForestGreen,
                    modifier = Modifier.size(24.dp).padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = recommendation.treatmentAction, 
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            
            if (recommendation.expertReferenceName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier.padding(start = 36.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Source, 
                            contentDescription = null, 
                            modifier = Modifier.size(14.dp).padding(top = 2.dp), 
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Source: ${recommendation.expertReferenceName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    if (recommendation.articleSourceUrl.isNotEmpty() && recommendation.articleSourceUrl.startsWith("http")) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "[Read Full Article]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(start = 22.dp)
                                .clickable {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(recommendation.articleSourceUrl))
                                        context.startActivity(intent)
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AuthScreen(
    strings: com.example.kingsentry.ui.AppStrings,
    dao: KingSentryDao,
    sessionManager: SessionManager,
    onSuccess: () -> Unit
) {
    var isSignupMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    
    // Auto-detect if we need to show signup for first-time use
    LaunchedEffect(Unit) {
        if (dao.getUserCount() == 0) {
            isSignupMode = true
        }
    }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isSignupMode) "Create Account" else "Login",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
                }
            }
        )

        if (isSignupMode) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (confirmPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                        Icon(
                            imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password"
                        )
                    }
                }
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val emailInput = email.trim().lowercase()
                if (emailInput.isBlank() || password.isBlank()) {
                    errorMessage = "Please fill all fields."
                    return@Button
                }

                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(emailInput).matches()) {
                    errorMessage = "Enter a valid email."
                    return@Button
                }

                if (isSignupMode && password != confirmPassword) {
                    errorMessage = "Passwords do not match."
                    return@Button
                }

                scope.launch {
                    val passwordHash = hashPassword(password)
                    
                    val localSuccess = if (isSignupMode) {
                        val newUser = User(username = emailInput, passwordHash = passwordHash)
                        val id = dao.insertUser(newUser)
                        if (id > 0) {
                            sessionManager.saveSession(id.toInt(), emailInput)
                            true
                        } else {
                            errorMessage = "Error creating account."
                            false
                        }
                    } else {
                        val user = dao.getUserByUsername(emailInput)
                        if (user != null && user.passwordHash == passwordHash) {
                            sessionManager.saveSession(user.id, emailInput)
                            true
                        } else {
                            errorMessage = "Invalid email or password."
                            false
                        }
                    }

                    if (localSuccess) {
                        val supabaseResult = try {
                            com.example.kingsentry.data.remote.SupabaseClientModule.signInToSupabase(emailInput, password)
                        } catch (e: Exception) {
                            android.util.Log.e("SupabaseAuth", "Error during Supabase authentication: ${e.message}", e)
                            false
                        }
                        android.util.Log.d("SupabaseAuth", "Supabase sign-in result: $supabaseResult")

                        com.example.kingsentry.workers.SyncWorker.enqueueOneTime(context, replaceExisting = true)
                        onSuccess()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(if (isSignupMode) "SIGN UP" else "LOGIN")
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = { isSignupMode = !isSignupMode }) {
            Text(if (isSignupMode) "Already have an account? Login" else "New user? Create Account")
        }
    }
}

/**
 * Simple SHA-256 password hashing.
 */
private fun hashPassword(password: String): String {
    val bytes = password.toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}
private fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

// --- Previews ---

@ComposePreview(showBackground = true, name = "Dashboard")
@Composable
fun DashboardPreview() {
    val strings = Localization[Language.ENGLISH]!!
    val context = LocalContext.current
    val dao = KingSentryDatabase.getDatabase(context).kingSentryDao()
    val sessionManager = SessionManager(context)
    KingSentryTheme {
        DashboardScreen(strings = strings, dao = dao, sessionManager = sessionManager, onScanClick = {})
    }
}

@ComposePreview(showBackground = true, name = "History")
@Composable
fun HistoryPreview() {
    val strings = Localization[Language.ENGLISH]!!
    val context = LocalContext.current
    val dao = KingSentryDatabase.getDatabase(context).kingSentryDao()
    val sessionManager = SessionManager(context)
    KingSentryTheme {
        HistoryScreen(strings = strings, dao = dao, sessionManager = sessionManager, onResultDetail = {})
    }
}

@ComposePreview(showBackground = true, name = "Settings")
@Composable
fun SettingsPreview() {
    val strings = Localization[Language.ENGLISH]!!
    KingSentryTheme {
        SettingsScreen(strings = strings, currentLanguage = Language.ENGLISH, onLanguageChange = {})
    }
}

@ComposePreview(showBackground = true, name = "Disease Library")
@Composable
fun DiseaseLibraryPreview() {
    val strings = Localization[Language.ENGLISH]!!
    val context = LocalContext.current
    val dao = KingSentryDatabase.getDatabase(context).kingSentryDao()
    KingSentryTheme {
        DiseaseLibraryScreen(strings = strings, dao = dao, onDiseaseClick = {})
    }
}

@ComposePreview(showBackground = true, name = "Result Healthy")
@Composable
fun ResultHealthyPreview() {
    val strings = Localization[Language.ENGLISH]!!
    KingSentryTheme {
        ResultScreen(
            strings = strings,
            result = MockResults.healthy,
            orchardName = "Backyard Trees",
            timestamp = System.currentTimeMillis(),
            capturedBitmap = null,
            detections = emptyList(),
            onSave = {},
            onBack = {}
        )
    }
}

@ComposePreview(showBackground = true, name = "Main Screen")
@Composable
fun MainScreenPreview() {
    KingSentryTheme {
        MainScreen()
    }
}
