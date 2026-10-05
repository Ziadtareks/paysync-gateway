package com.paysync.gateway.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.paysync.gateway.PaySyncApp
import com.paysync.gateway.R
import com.paysync.gateway.service.PaymentForegroundService
import com.paysync.gateway.ui.nav.Routes
import com.paysync.gateway.ui.screens.DashboardScreen
import com.paysync.gateway.ui.screens.PermissionsScreen
import com.paysync.gateway.ui.screens.SettingsScreen
import com.paysync.gateway.ui.theme.PaySyncTheme
import com.paysync.gateway.util.LocaleHelper
import com.paysync.gateway.work.PollingWorker

/**
 * Single-activity host.
 *
 * Glitch fixes implemented:
 * 1. **Permission State Lag** — [LifecycleEventObserver] on ON_RESUME pushes
 *    permission truth to [MainViewModel] immediately, so the UI recomposes
 *    the instant the user returns from a system settings dialog.
 * 2. **Language Dropdown Hang** — `expanded = false` is set *before* calling
 *    [MainViewModel.setLanguage] inside each [DropdownMenuItem.onClick].
 * 3. **Network Monitor** — Not directly here; see [NetworkMonitor] +
 *    [MainViewModel] init block (Dispatchers.Main.immediate collection).
 */
class MainActivity : AppCompatActivity() {

    private val container by lazy { (application as PaySyncApp).container }
    private val vm: MainViewModel by viewModels { MainViewModelFactory(container) }

    private var pendingStartAfterGrant = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (pendingStartAfterGrant && hasSmsPermissions()) {
                pendingStartAfterGrant = false
                startGateway()
            } else {
                pendingStartAfterGrant = false
            }
            // Immediately push the new permission state to the ViewModel
            pushPermissionStates()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Glitch Fix #2: Permission State Lag ──────────────────────
        // ON_RESUME fires when returning from system dialogs, granting
        // permissions, or switching back from other apps.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pushPermissionStates()
            }
        })

        pushPermissionStates()
        val start = if (hasSmsPermissions()) Routes.DASHBOARD else Routes.PERMISSIONS

        setContent {
            PaySyncTheme {
                GatewayNav(startDestination = start)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pushPermissionStates()
        // Opt-out update check (throttled to once per 24h inside the VM).
        vm.maybeCheckForUpdate()
    }

    /**
     * Recomputes platform permission state and pushes directly to
     * [MainViewModel.refreshPermissions] so the UI recomposes immediately.
     */
    private fun pushPermissionStates() {
        vm.refreshPermissions(
            sms = hasSmsPermissions(),
            notif = hasNotifPermission(),
            battery = isBatteryExempt()
        )
        vm.refresh()
    }

    // ─── Gateway Service Lifecycle ────────────────────────────────────

    private fun onToggleService(on: Boolean) {
        if (on) {
            if (!hasSmsPermissions()) {
                pendingStartAfterGrant = true
                requestSmsPermissions()
                vm.setServiceOn(false)
                return
            }
            startGateway()
        } else {
            PaymentForegroundService.stop(this)
            container.settings.serviceEnabled = false
            vm.setServiceOn(false)
        }
    }

    private fun startGateway() {
        PaymentForegroundService.start(this)
        container.settings.serviceEnabled = true
        container.settings.touchHeartbeat()
        runCatching { PollingWorker.schedule(this) }
        vm.setServiceOn(true)
        vm.refresh()
    }

    // ─── Permission Checkers ──────────────────────────────────────────

    private fun hasSmsPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isBatteryExempt(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) {
        false
    }

    private fun requestSmsPermissions() {
        val needed = mutableListOf(Manifest.permission.RECEIVE_SMS)
        if (!hasNotifPermission()) needed += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            Toast.makeText(this, getString(R.string.msg_notif_na), Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            if (isBatteryExempt()) {
                Toast.makeText(this, getString(R.string.msg_battery_done), Toast.LENGTH_SHORT).show()
                pushPermissionStates()
                return
            }
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (intent.resolveActivity(packageManager) != null) startActivity(intent)
            else startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    // ─── Navigation Shell ─────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GatewayNav(startDestination: String) {
        val navController = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val ctx = LocalContext.current

        LaunchedEffect(vm) {
            vm.events.collect { event ->
                if (event is UiEvent.Message) {
                    snackbar.showSnackbar(
                        event.arg?.let { ctx.getString(event.resId, it) }
                            ?: ctx.getString(event.resId)
                    )
                }
            }
        }

        val backStack by navController.currentBackStackEntryAsState()
        val current = backStack?.destination?.route

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = { LanguageMenu() },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                if (current != Routes.PERMISSIONS) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = current == Routes.DASHBOARD,
                            onClick = { navController.navigateTab(Routes.DASHBOARD) },
                            icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_dashboard)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                        NavigationBarItem(
                            selected = current == Routes.SETTINGS,
                            onClick = { navController.navigateTab(Routes.SETTINGS) },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_settings)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(padding)
            ) {
                composable(Routes.DASHBOARD) {
                    DashboardScreen(vm = vm, onToggle = ::onToggleService)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(vm = vm)
                }
                composable(Routes.PERMISSIONS) {
                    PermissionsScreen(
                        vm = vm,
                        onRequestSms = ::requestSmsPermissions,
                        onRequestNotifications = ::requestNotificationPermission,
                        onRequestBattery = ::requestIgnoreBatteryOptimizations,
                        onContinue = {
                            navController.navigate(Routes.DASHBOARD) {
                                popUpTo(Routes.PERMISSIONS) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }
        }
    }

    private fun androidx.navigation.NavHostController.navigateTab(route: String) {
        navigate(route) {
            popUpTo(Routes.DASHBOARD) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /**
     * Language switcher dropdown.
     *
     * **Glitch Fix #3: Language Dropdown Hang** — `expanded = false` is set
     * as the FIRST statement in each [DropdownMenuItem.onClick], guaranteeing
     * the menu closes before any language change / activity recreation occurs.
     */
    @Composable
    private fun LanguageMenu() {
        val lang by vm.appLang.collectAsStateWithLifecycle()
        var expanded by remember { mutableStateOf(false) }

        // Safety net: also close on language change (activity may recreate)
        LaunchedEffect(lang) { expanded = false }

        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    Icons.Filled.Language,
                    contentDescription = stringResource(R.string.lang_cd),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lang_english)) },
                    onClick = {
                        expanded = false          // ← Close FIRST
                        vm.setLanguage(LocaleHelper.LANG_EN)
                    },
                    trailingIcon = {
                        if (lang == LocaleHelper.LANG_EN) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lang_arabic)) },
                    onClick = {
                        expanded = false          // ← Close FIRST
                        vm.setLanguage(LocaleHelper.LANG_AR)
                    },
                    trailingIcon = {
                        if (lang == LocaleHelper.LANG_AR) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lang_system)) },
                    onClick = {
                        expanded = false          // ← Close FIRST
                        vm.setLanguage(LocaleHelper.LANG_SYSTEM)
                    },
                    trailingIcon = {
                        if (lang == LocaleHelper.LANG_SYSTEM) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}
