package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.DynamicApiEndpointHolder
import com.example.data.PranavAdminRepository
import com.example.data.PranavApiFactory
import com.example.security.OwnerKeystoreVault
import com.example.ui.AdminViewModel
import com.example.ui.PranavAdminApp
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val vault = OwnerKeystoreVault(applicationContext, BuildConfig.DEFAULT_API_BASE_URL)
    val endpointHolder = DynamicApiEndpointHolder(BuildConfig.DEFAULT_API_BASE_URL)
    val api = PranavApiFactory.create(endpointHolder)
    val repository = PranavAdminRepository(vault, endpointHolder, api)

    setContent {
      val adminVm: AdminViewModel = viewModel(factory = AdminViewModel.Factory(repository))
      val uiState by adminVm.uiState.collectAsStateWithLifecycle()

      MyApplicationTheme(darkTheme = uiState.darkTheme) {
        PranavAdminApp(state = uiState, vm = adminVm)
      }
    }
  }
}
