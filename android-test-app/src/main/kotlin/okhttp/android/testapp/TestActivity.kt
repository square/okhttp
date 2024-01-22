package okhttp.android.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class TestActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      val viewModel: TestViewModel = viewModel()

      val uiState by viewModel.uiState.collectAsStateWithLifecycle()

      Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
          Row {
            Button(onClick = { viewModel.startQuery() }) {
              Text("Query")
            }
            Button(onClick = { viewModel.clearAddressCache() }) {
              Text("Clear Cache")
            }
          }
          Text("Host: ${uiState.host}")
          val result = uiState.result
          when {
            result == null -> {}
            result.isSuccess -> {
              Text("${uiState.timestamp} ${result.getOrNull()}")
            }
            result.isFailure -> {
              Text("${result.exceptionOrNull()?.toString()}")
            }
          }
        }
      }
    }
  }
}

