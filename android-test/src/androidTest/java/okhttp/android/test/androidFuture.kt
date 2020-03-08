package okhttp.android.test

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.security.ProviderInstaller
import okhttp3.OkHttpClient
import okhttp3.android.checkIsAndroid
import okhttp3.internal.platform.Platform

interface AppForegroundStatusListener {
  fun onMoveToForeground()

  fun onMoveToBackground()
}

enum class BackgroundActivity {
  ACTIVE, LOW_ACTIVITY, ADAPTIVE, INACTIVE
}

fun watchBackgroundStatus(listener: AppForegroundStatusListener) {
  ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleObserver {
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onMoveToForeground() {
      listener.onMoveToForeground()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onMoveToBackground() {
      listener.onMoveToBackground()
    }
  })
}

fun OkHttpClient.Builder.watchBackgroundStatus(behaviour: BackgroundActivity = BackgroundActivity.LOW_ACTIVITY) {
  watchBackgroundStatus(object: AppForegroundStatusListener {
    override fun onMoveToForeground() {
      // TODO
    }

    override fun onMoveToBackground() {
      // TODO
    }
  })
}

fun watchBackgroundStatus() {
  watchBackgroundStatus(object: AppForegroundStatusListener {
    override fun onMoveToForeground() {
      Platform.get().isBackgrounded = false
    }

    override fun onMoveToBackground() {
      Platform.get().isBackgrounded = true
    }
  })

  // TODO get current status correctly
  Platform.get().isBackgrounded = false
}

fun enableGooglePlayServicesProvider(applicationContext: Context) {
  checkIsAndroid()

  try {
    ProviderInstaller.installIfNeeded(applicationContext)
  } catch (e: GooglePlayServicesNotAvailableException) {
    Platform.get().log("Google Play Services not available", Platform.WARN, e)
  } catch (e: GooglePlayServicesRepairableException) {
    // GooglePlayServicesRepairableException standard flow allows inline upgrade
    // see https://developers.google.com/android/reference/com/google/android/gms/common/GooglePlayServicesRepairableException
    Platform.get().log("Google Play Services out of date", Platform.WARN, e)
  }

//  try {
//    val method = Class.forName("com.google.android.gms.security.ProviderInstaller")
//        .getMethod("installIfNeeded", Context::class.java)
//
//    method.invoke(null, applicationContext)
//  } catch (e: ReflectiveOperationException) {
//    Platform.get().log("Google Play Services Provider not on classpath", Platform.WARN, e)
//  } catch (ite: InvocationTargetException) {
//    val e = ite.targetException
//
//    when (e.javaClass.simpleName) {
//      "GooglePlayServicesNotAvailableException" -> Platform.get().log("Google Play Services not available", Platform.WARN, e)
//      "GooglePlayServicesRepairableException" -> {
//        // GooglePlayServicesRepairableException standard flow allows inline upgrade
//        // see https://developers.google.com/android/reference/com/google/android/gms/common/GooglePlayServicesRepairableException
//        Platform.get().log("Google Play Services out of date", Platform.WARN, e)
//      }
//      else -> throw e
//    }
//  }
}
