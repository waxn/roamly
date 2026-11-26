package com.roamly.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roamly.R
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class Step { SignIn, Email, Totp }

/** Sign in to your own Roamly server; then a new-device email code or 2FA, when asked. */
@Composable
fun LoginScreen(viewModel: AuthViewModel, onLoggedIn: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val haptics = rememberHaptics()
    val c = Atlas.colors
    LaunchedEffect(isLoggedIn) { if (isLoggedIn == true) { haptics.success(); onLoggedIn() } }
    LaunchedEffect(state.error) { if (state.error != null) haptics.reject() }

    val step = when {
        state.needsTotp -> Step.Totp
        state.needsVerification -> Step.Email
        else -> Step.SignIn
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        DriftingContours(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            Image(painterResource(R.drawable.roamly_favicon), "Roamly", Modifier.size(76.dp))
            Spacer(Modifier.height(18.dp))
            Text("Roamly", style = MaterialTheme.typography.displayMedium, color = c.text)
            Text("Everywhere you've been, on your own server.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            Spacer(Modifier.height(30.dp))

            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(22.dp)) {
                AnimatedContent(
                    step,
                    transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut()) },
                    label = "loginStep",
                ) { s ->
                    Column {
                        when (s) {
                            Step.SignIn -> SignIn(state, viewModel)
                            Step.Email -> CodeStep(
                                title = "Check your email",
                                subtitle = if (state.cameFromTotp) "We sent a 6-digit code to ${state.verifyEmail}" else "New device — we sent a 6-digit code to ${state.verifyEmail}",
                                code = state.code, onCode = viewModel::onCodeChange,
                                error = state.error, loading = state.isLoading, onVerify = viewModel::verify,
                                alt = if (state.cameFromTotp) "Use your authenticator app instead" to viewModel::useTotpInstead else null,
                                onCancel = viewModel::cancelVerification,
                            )
                            Step.Totp -> CodeStep(
                                title = "Two-factor authentication",
                                subtitle = "Enter the code from your authenticator app, or a backup code",
                                code = state.totpCode, onCode = viewModel::onTotpCodeChange,
                                error = state.error, loading = state.isLoading, onVerify = viewModel::verifyTotp,
                                alt = if (state.totpEmailAvailable) (if (state.verifyEmail.isNotBlank()) "Email a code to ${state.verifyEmail} instead" else "Email me a code instead") to viewModel::useEmailCodeInstead else null,
                                onCancel = viewModel::cancelTotp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudOff, null, tint = c.textFaint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tracks offline, syncs when you're back online", style = MaterialTheme.typography.bodySmall, color = c.textFaint)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColumnScope.SignIn(state: LoginUiState, viewModel: AuthViewModel) {
    val c = Atlas.colors
    var showPassword by remember { mutableStateOf(false) }
    Text("Welcome back", style = MaterialTheme.typography.headlineSmall, color = c.text)
    Text("Connect to your Roamly server", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
    Spacer(Modifier.height(20.dp))
    AtlasField(state.serverUrl, viewModel::onServerUrlChange, label = "Server", placeholder = "https://roamly.example.com", leading = Icons.Rounded.Dns,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
    Spacer(Modifier.height(12.dp))
    AtlasField(state.username, viewModel::onUsernameChange, label = "Username", leading = Icons.Rounded.Person,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
    Spacer(Modifier.height(12.dp))
    AtlasField(
        state.password, viewModel::onPasswordChange, label = "Password", leading = Icons.Rounded.Lock,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (!state.isLoading) viewModel.login() }),
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconAction(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                if (showPassword) "Hide password" else "Show password", { showPassword = !showPassword },
                container = null, tint = c.textMuted, size = 40.dp, feel = Feel.Tick)
        },
    )
    state.error?.let { Spacer(Modifier.height(14.dp)); ErrorNote(it) }
    Spacer(Modifier.height(20.dp))
    AtlasButton("Sign in", viewModel::login, Modifier.fillMaxWidth(), loading = state.isLoading)
}

@Composable
private fun ColumnScope.CodeStep(
    title: String,
    subtitle: String,
    code: String,
    onCode: (String) -> Unit,
    error: String?,
    loading: Boolean,
    onVerify: () -> Unit,
    alt: Pair<String, () -> Unit>?,
    onCancel: () -> Unit,
) {
    val c = Atlas.colors
    Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
    Spacer(Modifier.height(20.dp))
    AtlasField(
        code, onCode, placeholder = "••••••",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!loading) onVerify() }),
    )
    error?.let { Spacer(Modifier.height(14.dp)); ErrorNote(it) }
    Spacer(Modifier.height(20.dp))
    AtlasButton("Verify", onVerify, Modifier.fillMaxWidth(), loading = loading)
    if (alt != null) {
        Spacer(Modifier.height(6.dp))
        AtlasButton(alt.first, alt.second, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, compact = true, enabled = !loading)
    }
    AtlasButton("Use a different account", onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, compact = true)
}

/** Slow topographic rings drifting behind the form. */
@Composable
private fun DriftingContours(modifier: Modifier) {
    val c = Atlas.colors
    val t = rememberInfiniteTransition(label = "contours")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val cx = size.width * 0.85f; val cy = size.height * 0.12f
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.18f), Color.Transparent), Offset(cx, cy), size.width * 0.8f), size.width * 0.8f, Offset(cx, cy))
        for (i in 1..9) {
            val r = size.minDimension * 0.12f * i
            val path = Path()
            for (s in 0..90) {
                val a = s / 90f * 2f * PI.toFloat()
                val w = 1f + 0.07f * sin(3 * a + phase + i * 0.7f) + 0.04f * cos(5 * a - phase)
                val x = cx + cos(a) * r * w
                val y = cy + sin(a) * r * w * 0.9f
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, (if (i == 3) c.accent.copy(alpha = 0.28f) else c.line), style = Stroke(1.2.dp.toPx()))
        }
    }
}
