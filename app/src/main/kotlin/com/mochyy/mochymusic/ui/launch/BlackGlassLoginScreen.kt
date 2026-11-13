// BlackGlassLoginScreen.kt
//
// Black Glass Music login gate. Front-end only for v1: there is no account
// server, so Log In / Create Account / Google / Guest simply mark the local
// "logged in" flag and let the user into the app. The flag lives in its own
// SharedPreferences file so it never touches the app's own preference store.

package com.mochyy.mochymusic.ui.launch

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BlackGlassAuth {
    private const val PREFS = "blackglass"
    private const val KEY_LOGGED_IN = "logged_in"

    fun isLoggedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOGGED_IN, false)

    fun setLoggedIn(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()
    }
}

private val Gold = Color(0xFFD4AF37)
private val GlassFill = Color(0x0FFFFFFF)
private val GlassBorder = Color(0x2EFFFFFF)

@Composable
fun BlackGlassLoginScreen(
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    var createMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showForgotHint by remember { mutableStateOf(false) }

    val proceed = {
        BlackGlassAuth.setLoggedIn(context)
        onLoggedIn()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x33D4AF37), Color.Transparent),
                    radius = 900f,
                )
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Gold ring logo
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .border(2.dp, Gold, CircleShape)
                    .background(Color(0x14D4AF37)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "♪", color = Gold, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Musify",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Your music, in black & gold",
                color = Color(0xFFBDBDBD),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(28.dp))

            // Glass card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(GlassFill)
                    .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                    .padding(20.dp),
            ) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Gold,
                    unfocusedBorderColor = GlassBorder,
                    focusedLabelColor = Gold,
                    unfocusedLabelColor = Color(0xFFBDBDBD),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Gold,
                    focusedLeadingIconColor = Gold,
                    unfocusedLeadingIconColor = Color(0xFF9E9E9E),
                )

                if (createMode) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = Color(0xFFBDBDBD),
                            )
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!createMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showForgotHint = !showForgotHint }) {
                            Text("Forgot password?", color = Gold, fontSize = 13.sp)
                        }
                    }
                    if (showForgotHint) {
                        Text(
                            text = "Password reset arrives in a later update — for now, continue as guest below.",
                            color = Color(0xFFBDBDBD),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = proceed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gold,
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text(
                        text = if (createMode) "Create Account" else "Log In",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.weight(1f).height(1.dp).background(GlassBorder))
                    Text(
                        text = "  or  ",
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp,
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(GlassBorder))
                }
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = proceed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x1AFFFFFF),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("G", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "  Continue with Google",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = proceed) {
                        Text("Continue as guest", color = Color(0xFFBDBDBD), fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (createMode) "Already have an account?" else "New here?",
                    color = Color(0xFFBDBDBD),
                    fontSize = 14.sp,
                )
                TextButton(onClick = { createMode = !createMode }) {
                    Text(
                        text = if (createMode) "Log in" else "Create account",
                        color = Gold,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
