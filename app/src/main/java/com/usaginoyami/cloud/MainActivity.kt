package com.usaginoyami.cloud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.usaginoyami.cloud.rclone.RcloneService
import com.usaginoyami.cloud.ui.AddAccountScreen
import com.usaginoyami.cloud.ui.BrowserScreen
import com.usaginoyami.cloud.ui.HomeScreen
import com.usaginoyami.cloud.ui.SearchScreen
import com.usaginoyami.cloud.ui.UsagiTheme

class MainActivity : ComponentActivity() {

    private val vm: CloudViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UsagiTheme {
                val snackbar = remember { SnackbarHostState() }
                val authUrl by RcloneService.authUrl.collectAsState()
                val engineError by RcloneService.error.collectAsState()
                val ready by RcloneService.ready.collectAsState()

                // Abre o login OAuth (Google, OneDrive, Dropbox...) no navegador
                LaunchedEffect(authUrl) {
                    authUrl?.let {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                        RcloneService.clearAuthUrl()
                    }
                }
                LaunchedEffect(vm.message) {
                    vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
                }
                LaunchedEffect(engineError) {
                    engineError?.let { snackbar.showSnackbar(it) }
                }

                BackHandler(enabled = vm.screen !is Screen.Home) { vm.back() }

                Surface(Modifier.fillMaxSize()) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                        when (val s = vm.screen) {
                            Screen.Home -> HomeScreen(vm, ready, engineError, pad)
                            is Screen.Browser -> BrowserScreen(vm, s, pad)
                            Screen.AddAccount -> AddAccountScreen(vm, pad)
                            Screen.Search -> SearchScreen(vm, pad)
                        }
                    }
                }
            }
        }
    }
}
