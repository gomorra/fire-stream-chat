package com.firestream.chat.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.R

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun LoginScreen(
    onOtpSent: (verificationId: String, phoneNumber: String) -> Unit,
    onAlreadyLoggedIn: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var countryCode by remember { mutableStateOf("+1") }
    var phoneNumber by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.isLoggedIn, uiState.isCheckingAuth) {
        if (!uiState.isCheckingAuth && uiState.isLoggedIn) {
            onAlreadyLoggedIn()
        }
    }

    // Show a spinner while the auth check is in progress or navigation is pending — avoids a blank white screen
    if (uiState.isCheckingAuth || uiState.isLoggedIn) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it.message, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = countryCode,
                    onValueChange = { countryCode = it },
                    label = { Text(stringResource(R.string.country_code_hint)) },
                    modifier = Modifier.width(100.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text(stringResource(R.string.phone_number_hint)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val fullNumber = "$countryCode$phoneNumber"
                    val activity = context.findActivity() ?: return@Button
                    viewModel.sendOtp(fullNumber, activity) { verificationId ->
                        onOtpSent(verificationId, fullNumber)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phoneNumber.length >= 7 && !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.send_otp))
                }
            }
        }
    }
}
