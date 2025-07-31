package io.github.sds100.keymapper.nativelib

import android.ddm.DdmHandleAppName
import android.system.Os
import android.util.Log
import kotlin.system.exitProcess

/**
 * See demo/service/UserService.java in the Shizuku-API repository for how a Shizuku user service
 * is set up.
 */
class EvdevService : IEvdevService.Stub() {

    /**
     * A native method that is implemented by the 'nativelib' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        private const val TAG: String = "EvdevService"

        @JvmStatic
        fun main(args: Array<String>) {
            DdmHandleAppName.setAppName("keymapper_evdev", 0)
            EvdevService()
        }
    }

    init {
        Log.e(TAG, "SYSTEM PROPERTY ${System.getProperty("shizuku.library.path")}")
        System.load("${System.getProperty("shizuku.library.path")}/libnativelib.so")
        stringFromJNI()
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        exitProcess(0)
    }

    override fun sendEvent(): String {
        Log.e(TAG, "UID = ${Os.getuid()}")
        return stringFromJNI()
    }
}
