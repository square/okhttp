package okhttp.android.testapp

import android.annotation.SuppressLint
import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.InetAddress
import java.time.LocalTime
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TestViewModel(application: Application) : AndroidViewModel(application) {

  init {
    viewModelScope.launch {
      while (true) {
        val dnsQuery = dnsQuery()
        val out = if (dnsQuery.isFailure) {
          val throwable = dnsQuery.exceptionOrNull()!!
          throwable.toString()
        } else {
          dnsQuery.getOrNull()?.toString()
        }
        println("looping(isFlightModeOn = ${isFlightModeOn()}) " + out)
        delay(250.milliseconds)
      }
    }
  }

  /**Query the system setting to check the flight mode is on or off**/
  private fun isFlightModeOn() = Settings.Global.getInt(
    getApplication<Application>().contentResolver,
    Settings.Global.AIRPLANE_MODE_ON,
    0
  ) == 1

  @SuppressLint("NewApi")
  fun startQuery() {
    viewModelScope.launch {
      uiState.update { it.copy(inProgress = true) }

      val result = dnsQuery()

      println(result)

      uiState.update { it.copy(inProgress = false, result = result, timestamp = LocalTime.now()) }
    }
  }

  private suspend fun dnsQuery(): Result<List<InetAddress>> {
    return withContext(Dispatchers.IO) {
      kotlin.runCatching {
        InetAddress.getAllByName(uiState.value.host).toList()
      }.recoverCatching {
        throw it.cause ?: it
      }
    }
  }

  val clearDnsCache = InetAddress::class.java.getMethod("clearDnsCache")

  fun clearAddressCache() {
//    println("clearAddressCache")
    clearDnsCache.invoke(null)
  }

  val uiState: MutableStateFlow<UiState> = MutableStateFlow(UiState())


}

data class UiState(
  val host: String = "www.google.com",
  val inProgress: Boolean = false,
  val result: Result<List<InetAddress>>? = null,
  val timestamp: LocalTime? = null
)
