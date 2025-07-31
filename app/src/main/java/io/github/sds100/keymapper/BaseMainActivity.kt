package io.github.sds100.keymapper

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import com.anggrayudi.storage.extension.openInputStream
import com.anggrayudi.storage.extension.openOutputStream
import com.anggrayudi.storage.extension.toDocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.sds100.keymapper.Constants.PACKAGE_NAME
import io.github.sds100.keymapper.compose.ComposeColors
import io.github.sds100.keymapper.databinding.ActivityMainBinding
import io.github.sds100.keymapper.nativelib.IEvdevService
import io.github.sds100.keymapper.nativelib.adb.AdbPairingService
import io.github.sds100.keymapper.system.accessibility.AccessibilityServiceAdapter
import io.github.sds100.keymapper.system.files.FileUtils
import io.github.sds100.keymapper.system.inputevents.MyMotionEvent
import io.github.sds100.keymapper.system.permissions.AndroidPermissionAdapter
import io.github.sds100.keymapper.system.permissions.RequestPermissionDelegate
import io.github.sds100.keymapper.system.root.SuAdapterImpl
import io.github.sds100.keymapper.trigger.RecordTriggerController
import io.github.sds100.keymapper.util.launchRepeatOnLifecycle
import io.github.sds100.keymapper.util.ui.showPopups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import timber.log.Timber

/**
 * Created by sds100 on 19/02/2020.
 */

abstract class BaseMainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SHOW_ACCESSIBILITY_SETTINGS_NOT_FOUND_DIALOG =
            "$PACKAGE_NAME.ACTION_SHOW_ACCESSIBILITY_SETTINGS_NOT_FOUND_DIALOG"

        const val ACTION_USE_FLOATING_BUTTONS =
            "$PACKAGE_NAME.ACTION_USE_FLOATING_BUTTONS"

        const val ACTION_SAVE_FILE = "$PACKAGE_NAME.ACTION_SAVE_FILE"
        const val EXTRA_FILE_URI = "$PACKAGE_NAME.EXTRA_FILE_URI"
    }

    private val permissionAdapter: AndroidPermissionAdapter by lazy {
        ServiceLocator.permissionAdapter(this)
    }

    val serviceAdapter: AccessibilityServiceAdapter by lazy {
        ServiceLocator.accessibilityServiceAdapter(this)
    }

    private val suAdapter: SuAdapterImpl by lazy {
        ServiceLocator.suAdapter(this)
    }

    val viewModel by viewModels<ActivityViewModel> {
        ActivityViewModel.Factory(ServiceLocator.resourceProvider(this))
    }

    private val currentNightMode: Int
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    private lateinit var requestPermissionDelegate: RequestPermissionDelegate
    private val recordTriggerController: RecordTriggerController by lazy {
        (applicationContext as KeyMapperApp).recordTriggerController
    }

    private var originalFileUri: Uri? = null

    private val saveFileLauncher =
        registerForActivityResult(CreateDocument(FileUtils.MIME_TYPE_ALL)) { uri ->
            uri ?: return@registerForActivityResult

            originalFileUri?.let { original -> saveFile(originalFile = original, targetFile = uri) }
        }

    /**
     * This is used when saving a file with the Android share sheet and want to copy
     * the private to the public location.
     */
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return

            when (intent.action) {
                ACTION_SAVE_FILE -> {
                    lifecycleScope.launch {
                        withStateAtLeast(Lifecycle.State.RESUMED) {
                            selectFileLocationAndSave(intent)
                        }
                    }
                }
            }
        }
    }

    private val evdevServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            val service = IEvdevService.Stub.asInterface(binder)

            lifecycleScope.launch(Dispatchers.Default) {
                Timber.e("RECEIVED FROM EVDEV ${service.sendEvent()}")
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                ComposeColors.surfaceContainerLight.toArgb(),
                ComposeColors.surfaceContainerDark.toArgb(),
            ),
            navigationBarStyle = SystemBarStyle.auto(
                ComposeColors.surfaceContainerLight.toArgb(),
                ComposeColors.surfaceContainerDark.toArgb(),
            ),
        )
        super.onCreate(savedInstanceState)

        if (viewModel.previousNightMode != currentNightMode) {
            ServiceLocator.resourceProvider(this).onThemeChange()
        }

        val binding =
            DataBindingUtil.setContentView<ActivityMainBinding>(this, R.layout.activity_main)

        viewModel.showPopups(this, binding.coordinatorLayout)

        requestPermissionDelegate = RequestPermissionDelegate(this, showDialogs = true)

        ServiceLocator.permissionAdapter(this@BaseMainActivity).request
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { permission ->
                requestPermissionDelegate.requestPermission(
                    permission,
                    findNavController(R.id.container),
                )
            }
            .launchIn(lifecycleScope)

        // Must launch when the activity is resumed
        // so the nav controller can be found
        launchRepeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (viewModel.handledActivityLaunchIntent) {
                return@launchRepeatOnLifecycle
            }

            when (intent?.action) {
                ACTION_SHOW_ACCESSIBILITY_SETTINGS_NOT_FOUND_DIALOG -> {
                    viewModel.onCantFindAccessibilitySettings()
                    viewModel.handledActivityLaunchIntent = true
                }
            }
        }

        IntentFilter().apply {
            addAction(ACTION_SAVE_FILE)

            ContextCompat.registerReceiver(
                this@BaseMainActivity,
                broadcastReceiver,
                this,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        // Go to build number.
//        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
//            val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
//            val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
//
//            putExtra(EXTRA_FRAGMENT_ARG_KEY, "build_number")
//            val bundle = bundleOf(EXTRA_FRAGMENT_ARG_KEY to "build_number")
//            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
//
//            startActivity(this)
//        }

        // Highlight wireless debugging setting
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            // See SubSettingLauncher in the Android Settings code for how to
            // launch a specific fragment.
            // https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Settings/src/com/android/settings/core/SubSettingLauncher.java
//            val EXTRA_SHOW_FRAGMENT = ":settings:show_fragment"
//            putExtra(EXTRA_SHOW_FRAGMENT, "com.android.settings.development.WirelessDebuggingFragment")
//            putExtra(":settings:source_metrics", 1831)

            val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
            val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

            putExtra(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
            val bundle = bundleOf(EXTRA_FRAGMENT_ARG_KEY to "toggle_adb_wireless")
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
//            startActivity(this)
        }

        // See demo.DemoActivity in the Shizuku-API repository.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            startPairingService()
            startAdb("127.0.0.1", 33697)
        }

//        val userServiceArgs =
//            UserServiceArgs(
//                ComponentName(
//                    BuildConfig.APPLICATION_ID,
//                    EvdevService::class.java.getName(),
//                ),
//            )
//                .daemon(false)
//                .processNameSuffix("service")
//                .debuggable(BuildConfig.DEBUG)
//                .version(BuildConfig.VERSION_CODE)
//
//        try {
//            if (Shizuku.getVersion() < 10) {
//                Timber.e("requires Shizuku API 10")
//            } else {
//                Shizuku.bindUserService(userServiceArgs, evdevServiceConnection)
//            }
//        } catch (tr: Throwable) {
//            tr.printStackTrace()
//        }
    }

    override fun onResume() {
        super.onResume()

        Timber.i("MainActivity: onResume. Version: ${Constants.VERSION}")

        // This must be after onResume to ensure all the fragment lifecycles' have also
        // resumed which are observing these events.
        // This is checked here and not in KeyMapperApp's lifecycle observer because
        // the activities have not necessarily resumed at that point.
        permissionAdapter.onPermissionsChanged()
        serviceAdapter.updateWhetherServiceIsEnabled()
        suAdapter.invalidateIsRooted()
    }

    override fun onDestroy() {
        UseCases.onboarding(this).shownAppIntro = true

        viewModel.previousNightMode = currentNightMode
        unregisterReceiver(broadcastReceiver)
        super.onDestroy()
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event ?: return super.onGenericMotionEvent(event)

        val consume =
            recordTriggerController.onActivityMotionEvent(MyMotionEvent.fromMotionEvent(event))

        return if (consume) {
            true
        } else {
            // IMPORTANT! return super so that the back navigation button still works.
            super.onGenericMotionEvent(event)
        }
    }

    private fun saveFile(originalFile: Uri, targetFile: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            targetFile.openOutputStream(this@BaseMainActivity)?.use { output ->
                originalFile.openInputStream(this@BaseMainActivity)?.use { input ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun selectFileLocationAndSave(intent: Intent) {
        val fileUri =
            IntentCompat.getParcelableExtra(intent, EXTRA_FILE_URI, Uri::class.java) ?: return

        val fileName = fileUri.toDocumentFile(this@BaseMainActivity)?.name ?: return

        originalFileUri = fileUri
        saveFileLauncher.launch(fileName)
    }

    private val sb = StringBuilder()

    fun postResult(throwable: Throwable? = null) {
        if (throwable == null) {
            Timber.e(sb.toString())
        } else {
            Timber.e(throwable)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startPairingService() {
        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
        } catch (e: Throwable) {
            Timber.e("startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                val mode = getSystemService(AppOpsManager::class.java)
                    .noteOpNoThrow(
                        "android:start_foreground",
                        android.os.Process.myUid(),
                        packageName,
                        null,
                        null,
                    )
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(
                        this,
                        "OP_START_FOREGROUND is denied. What are you doing?",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                startService(intent)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun writeStarterFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Starter.writeSdcardFiles(applicationContext)
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(this@BaseMainActivity)
                        .setTitle("Cannot write files")
                        .setMessage(Log.getStackTraceString(e))
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                        .apply {
                            setOnShowListener {
                                this.findViewById<TextView>(android.R.id.message)!!.apply {
                                    typeface = Typeface.MONOSPACE
                                    setTextIsSelectable(true)
                                }
                            }
                        }
                        .show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun startAdb(host: String, port: Int) {
        writeStarterFiles()

        sb.append("Starting with wireless adb...").append('\n').append('\n')
        postResult()

        GlobalScope.launch(Dispatchers.IO) {
            val key = try {
                AdbKey(
                    PreferenceAdbKeyStore(PreferenceManager.getDefaultSharedPreferences(this@BaseMainActivity)),
                    "shizuku",
                )
            } catch (e: Throwable) {
                e.printStackTrace()
                sb.append('\n').append(Log.getStackTraceString(e))

                postResult(AdbKeyException(e))
                return@launch
            }

            AdbClient(host, port, key).runCatching {
                connect()
                shellCommand(Starter.sdcardCommand) {
                    sb.append(String(it))
                    postResult()
                }
                close()
            }.onFailure {
                it.printStackTrace()

                sb.append('\n').append(Log.getStackTraceString(it))
                postResult(it)
            }

            /* Adb on MIUI Android 11 has no permission to access Android/data.
               Before MIUI Android 12, we can temporarily use /data/user_de.
               After that, is better to implement "adb push" and push files directly to /data/local/tmp.
             */
            if (sb.contains("/Android/data/${BuildConfig.APPLICATION_ID}/start.sh: Permission denied")) {
                sb.append('\n')
                    .appendLine("adb have no permission to access Android/data, how could this possible ?!")
                    .appendLine("try /data/user_de instead...")
                    .appendLine()
                postResult()

                Starter.writeDataFiles(application, true)

                AdbClient(host, port, key).runCatching {
                    connect()
                    shellCommand(Starter.dataCommand) {
                        sb.append(String(it))
                        postResult()
                    }
                    close()
                }.onFailure {
                    it.printStackTrace()

                    sb.append('\n').append(Log.getStackTraceString(it))
                    postResult(it)
                }
            }
        }
    }
}
