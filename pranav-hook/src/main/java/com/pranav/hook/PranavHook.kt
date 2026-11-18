package com.pranav.hook

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.pranav.sdk.PranavConfiguration
import com.pranav.sdk.PranavSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PranavHookConfig(
  val sdkConfiguration: PranavConfiguration,
  val autoConnectOnLaunch: Boolean = true,
  val minSupportedApi: Int = 24,
)

data class PranavHookStatus(
  val attached: Boolean = false,
  val compatible: Boolean = true,
  val activeActivities: Int = 0,
  val lastActivityName: String = "",
  val lastError: String? = null,
)

/**
 * Separate PRANAV Hook Integration Module.
 *
 * Bridges host Application & Activity lifecycles to PRANAV SDK cleanly with full crash isolation.
 * Strictly separate from PRANAV.dex and never modifies third-party binaries.
 */
object PranavHook {
  private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
  private var hookScope: CoroutineScope? = null
  private val activeActivitySet = LinkedHashSet<String>()

  private val _status = MutableStateFlow(PranavHookStatus())
  val statusFlow: StateFlow<PranavHookStatus> = _status.asStateFlow()

  @Synchronized
  fun attachApplication(application: Application, config: PranavHookConfig): PranavHookStatus {
    return runCatching {
      val compatible = Build.VERSION.SDK_INT >= config.minSupportedApi
      if (!compatible) {
        val status =
          PranavHookStatus(
            attached = false,
            compatible = false,
            lastError = "Android API ${Build.VERSION.SDK_INT} < minSupportedApi ${config.minSupportedApi}",
          )
        _status.value = status
        return status
      }

      PranavSdk.initialize(application.applicationContext, config.sdkConfiguration)

      lifecycleCallbacks?.let { application.unregisterActivityLifecycleCallbacks(it) }
      activeActivitySet.clear()

      val callbacks =
        object : Application.ActivityLifecycleCallbacks {
          override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            runCatching {
              activeActivitySet.add(activity.javaClass.name)
              _status.value =
                _status.value.copy(
                  activeActivities = activeActivitySet.size,
                  lastActivityName = activity.javaClass.simpleName,
                )
            }
          }

          override fun onActivityStarted(activity: Activity) = Unit

          override fun onActivityResumed(activity: Activity) {
            runCatching {
              _status.value = _status.value.copy(lastActivityName = activity.javaClass.simpleName)
            }
          }

          override fun onActivityPaused(activity: Activity) = Unit
          override fun onActivityStopped(activity: Activity) = Unit
          override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

          override fun onActivityDestroyed(activity: Activity) {
            runCatching {
              activeActivitySet.remove(activity.javaClass.name)
              _status.value = _status.value.copy(activeActivities = activeActivitySet.size)
            }
          }
        }

      application.registerActivityLifecycleCallbacks(callbacks)
      lifecycleCallbacks = callbacks

      hookScope?.cancel()
      hookScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      if (config.autoConnectOnLaunch) {
        hookScope?.launch { runCatching { PranavSdk.connect() } }
      }

      val attachedStatus = PranavHookStatus(attached = true, compatible = true)
      _status.value = attachedStatus
      attachedStatus
    }.getOrElse { err ->
      val errStatus = PranavHookStatus(attached = false, compatible = true, lastError = err.message)
      _status.value = errStatus
      errStatus
    }
  }

  @Synchronized
  fun detach(application: Application) {
    runCatching {
      lifecycleCallbacks?.let { application.unregisterActivityLifecycleCallbacks(it) }
      lifecycleCallbacks = null
      activeActivitySet.clear()
      hookScope?.cancel()
      hookScope = null
      PranavSdk.shutdown()
      _status.value = PranavHookStatus(attached = false)
    }
  }
}
