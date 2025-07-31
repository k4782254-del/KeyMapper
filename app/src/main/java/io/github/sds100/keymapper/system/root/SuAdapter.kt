package io.github.sds100.keymapper.system.root

import com.topjohnwu.superuser.Shell
import io.github.sds100.keymapper.system.SimpleShell
import io.github.sds100.keymapper.system.permissions.Permission
import io.github.sds100.keymapper.util.Error
import io.github.sds100.keymapper.util.Result
import io.github.sds100.keymapper.util.Success
import io.github.sds100.keymapper.util.firstBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.io.IOException
import java.io.InputStream

/**
 * Created by sds100 on 21/04/2021.
 */

class SuAdapterImpl(
    coroutineScope: CoroutineScope,
) : SuAdapter {
    private var process: Process? = null

    override val isRooted: MutableStateFlow<Boolean> = MutableStateFlow(false)

    init {
        invalidateIsRooted()
    }

    override fun requestPermission(): Boolean {
        // show the su prompt
        Shell.getShell()

        return isRooted.updateAndGet { Shell.isAppGrantedRoot() ?: false }
    }

    override fun execute(command: String, block: Boolean): Result<*> {
        if (!isRooted.firstBlocking()) {
            return Error.PermissionDenied(Permission.ROOT)
        }

        try {
            if (block) {
                Shell.cmd(command).exec()
            } else {
                Shell.cmd(command).submit()
            }

            return Success(Unit)
        } catch (e: Exception) {
            return Error.Exception(e)
        }
    }

    override fun getCommandOutput(command: String): Result<InputStream> {
        if (!isRooted.firstBlocking()) {
            return Error.PermissionDenied(Permission.ROOT)
        }

        try {
            val inputStream = SimpleShell.getShellCommandStdOut("su", "-c", command)
            return Success(inputStream)
        } catch (e: IOException) {
            return Error.UnknownIOError
        }
    }

    fun invalidateIsRooted() {
        Shell.getShell()
        isRooted.update { Shell.isAppGrantedRoot() ?: false }
    }
}

interface SuAdapter {
    val isRooted: StateFlow<Boolean>

    /**
     * @return whether root permission was granted successfully
     */
    fun requestPermission(): Boolean
    fun execute(command: String, block: Boolean = false): Result<*>
    fun getCommandOutput(command: String): Result<InputStream>
}
