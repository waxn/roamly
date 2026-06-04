package com.roamly.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.roamly.R
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClaySurface

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoggedIn: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    var showPassword by remember { mutableStateOf(false) }
    val clay = Clay.colors

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn == true) onLoggedIn()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(clay.backgroundBrush)
    ) {
        // A couple of soft brand blobs floating behind the card for depth.
        BrandBlob(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 24.dp), clay.primaryGradient, 150.dp)
        BrandBlob(Modifier.align(Alignment.BottomStart).padding(bottom = 80.dp, start = 8.dp), clay.secondaryGradient, 180.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            // Big puffy logo badge
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(clay.primaryGradient)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_roamly_mark),
                    contentDescription = "Roamly",
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "Roamly",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "your journeys, beautifully mapped",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            ClaySurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        "Welcome back",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Connect to your Roamly server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))

                    ClayField(
                        value = state.serverUrl,
                        onValueChange = viewModel::onServerUrlChange,
                        label = "Server URL",
                        placeholder = "https://your-server.com",
                        keyboardType = KeyboardType.Uri,
                    )
                    Spacer(Modifier.height(12.dp))
                    ClayField(
                        value = state.username,
                        onValueChange = viewModel::onUsernameChange,
                        label = "Username",
                        keyboardType = KeyboardType.Text,
                    )
                    Spacer(Modifier.height(12.dp))
                    ClayField(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = "Password",
                        keyboardType = KeyboardType.Password,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )

                    if (state.error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    ClayButton(
                        onClick = viewModel::login,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Text("Sign in", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "tracks offline · syncs when you're back online",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ClayField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ),
    )
}

@Composable
private fun BrandBlob(modifier: Modifier, gradient: List<Color>, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(
                Brush.radialGradient(
                    listOf(gradient.first().copy(alpha = 0.20f), Color.Transparent)
                )
            )
    )
}
