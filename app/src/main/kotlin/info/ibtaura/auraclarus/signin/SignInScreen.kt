package info.ibtaura.auraclarus.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import info.ibtaura.aura.core.AuraError
import info.ibtaura.aura.core.AuthStep
import info.ibtaura.auraclarus.design.AuraCard
import info.ibtaura.auraclarus.design.AuraColors
import info.ibtaura.auraclarus.design.AuraPage
import info.ibtaura.auraclarus.design.AuraTextField
import info.ibtaura.auraclarus.design.AuraType
import info.ibtaura.auraclarus.design.PillButton
import info.ibtaura.auraclarus.design.PillStyle
import info.ibtaura.auraclarus.design.SectionTitle
import info.ibtaura.auraclarus.shell.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Email, then the code from the email (SignIn/SignInView.swift). The Figma has no frame for this; it uses the same
 * cards and pills. Never log the email or the code.
 */
@Composable
fun SignInScreen(app: AppState) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var step by remember { mutableStateOf<AuthStep?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var noticeText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun begin() {
        busy = true
        errorText = null
        noticeText = null
    }

    fun fail(error: Throwable) {
        app.note(error)
        errorText = SignInCodeRules.plainMessage(error)
    }

    fun run(block: suspend () -> Unit) {
        begin()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
            busy = false
        }
    }

    val trimmedEmail = email.trim()
    val emailLooksValid = trimmedEmail.split("@").let { parts ->
        parts.size == 2 && parts[0].isNotEmpty() && parts[1].contains(".")
    }

    AuraPage {
        val current = step
        SectionTitle(if (current == null) "Sign in" else "Enter your code")
        if (current != null) {
            SignInCodeCard(current, code) { code = it }
        } else {
            SignInEmailCard(email) { email = it }
        }

        val error = errorText
        val notice = noticeText
        if (error != null) {
            Text(error, style = AuraType.callout, color = AuraColors.red)
        } else if (notice != null) {
            Text(notice, style = AuraType.callout, color = AuraColors.gray)
        }

        if (current == null) {
            PillButton(
                if (busy) "Sending..." else "Send code",
                onClick = {
                    val address = trimmedEmail
                    run {
                        val next = app.auth.requestCode(address)
                        code = ""
                        step = next
                    }
                },
                modifier = Modifier.testTag("signin-send-code"),
                fills = true,
                enabled = !busy && emailLooksValid,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    if (busy) "Checking..." else "Continue",
                    onClick = {
                        val typed = code
                        run { app.authState = app.auth.finish(current, typed) }
                    },
                    modifier = Modifier.testTag("signin-continue"),
                    fills = true,
                    enabled = !busy && code.length in SignInCodeRules.allowedLengths(current),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(
                        "Back",
                        onClick = {
                            step = null
                            code = ""
                            errorText = null
                            noticeText = null
                        },
                        modifier = Modifier.weight(1f).testTag("signin-back"),
                        style = PillStyle.Secondary,
                        fills = true,
                        enabled = !busy,
                    )
                    PillButton(
                        "Resend",
                        onClick = {
                            run {
                                val next = app.auth.resend(current)
                                code = ""
                                step = next
                                noticeText = "We sent a new code."
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("signin-resend"),
                        style = PillStyle.Secondary,
                        fills = true,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/** The code lengths a step accepts, and errors in plain words. */
internal object SignInCodeRules {
    /** An unconfirmed account can hide as a sign-in, so such a step takes both lengths: 8 signs in, 6 confirms. */
    fun allowedLengths(step: AuthStep): List<Int> {
        if (!step.alsoConfirm) return listOf(step.codeLength)
        val other = if (step.codeLength == 8) 6 else 8
        return listOf(step.codeLength, other).sorted()
    }

    fun plainMessage(error: Throwable): String {
        val fallback = "That didn't work. Please try again."
        return when (error) {
            AuraError.Offline -> "You're offline. Check your connection and try again."
            is AuraError.Server -> error.message.ifEmpty { fallback }
            else -> fallback
        }
    }
}

@Composable
private fun SignInEmailCard(email: String, onChange: (String) -> Unit) {
    AuraCard {
        Text("We'll email you a code to sign in.", style = AuraType.calloutMedium, color = Color.Black)
        AuraTextField(
            value = email,
            onValueChange = onChange,
            placeholder = "Email",
            modifier = Modifier.testTag("signin-email"),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
        )
    }
}

@Composable
private fun SignInCodeCard(step: AuthStep, code: String, onChange: (String) -> Unit) {
    val lengths = SignInCodeRules.allowedLengths(step)
    val sentTo = step.destination.ifEmpty { step.email }
    val digits = lengths.joinToString(" or ")
    AuraCard {
        Text("Enter the $digits-digit code we sent to $sentTo.", style = AuraType.calloutMedium, color = Color.Black)
        AuraTextField(
            value = code,
            // Digits only, up to the longest length this step takes (pasted codes can hold spaces).
            onValueChange = { typed -> onChange(typed.filter { it in '0'..'9' }.take(lengths.last())) },
            placeholder = "Code",
            modifier = Modifier.testTag("signin-code"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
    }
}
