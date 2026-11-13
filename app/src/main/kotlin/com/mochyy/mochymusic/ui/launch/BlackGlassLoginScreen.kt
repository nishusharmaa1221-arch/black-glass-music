// BlackGlassLoginScreen.kt
//
// Musify login gate — REAL accounts backed by Firebase Authentication.
// - One account per email: Firebase itself rejects a second signup with the
//   same email (FirebaseAuthUserCollisionException).
// - Unique usernames: before signup we claim usernames/{username} in
//   Firestore; a name that already exists is rejected, so two accounts can
//   never share a username.
// - Login only succeeds with the correct email + password.
// The session is Firebase's own persisted session: once signed in, the gate
// stays open across restarts until the user signs out.

package com.mochyy.mochymusic.ui.launch

import android.content.Context
import android.util.Patterns
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore

object BlackGlassAuth {
    fun isLoggedIn(context: Context): Boolean =
        runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false)

    fun signOut() {
        runCatching { FirebaseAuth.getInstance().signOut() }
    }
}

private val Gold = Color(0xFFD4AF37)
private val GlassFill = Color(0x0FFFFFFF)
private val GlassBorder = Color(0x2EFFFFFF)
private val ErrorRed = Color(0xFFFF8A80)
private val InfoGray = Color(0xFFBDBDBD)

private fun authErrorMessage(e: Exception, createMode: Boolean): String = when (e) {
    is FirebaseAuthUserCollisionException ->
        "Is email se account pehle se bana hai — Login karo"
    is FirebaseAuthWeakPasswordException ->
        "Password kamzor hai — kam se kam 6 characters rakho"
    is FirebaseAuthInvalidUserException ->
        "Is email se koi account nahi hai — pehle account banao"
    is FirebaseAuthInvalidCredentialsException ->
        if (createMode) "Email sahi format mein likho" else "Email ya password galat hai"
    is FirebaseTooManyRequestsException ->
        "Bahut zyada koshishein ho gayi hain — thodi der baad try karo"
    is FirebaseNetworkException ->
        "Internet nahi chal raha — connection check karo"
    else -> "Kuch gadbad ho gayi — dobara try karo"
}

@Composable
fun BlackGlassLoginScreen(
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    var createMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun fail(msg: String) {
        loading = false
        error = msg
        info = null
    }

    fun doLogin() {
        val em = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(em).matches()) {
            fail("Email sahi se likho")
            return
        }
        if (password.isEmpty()) {
            fail("Password likho")
            return
        }
        loading = true
        error = null
        info = null
        auth.signInWithEmailAndPassword(em, password)
            .addOnSuccessListener { onLoggedIn() }
            .addOnFailureListener { e -> fail(authErrorMessage(e, createMode = false)) }
    }

    fun doCreate() {
        val uname = username.trim().lowercase()
        val em = email.trim()
        if (!Regex("^[a-z0-9_.]{3,20}$").matches(uname)) {
            fail("Username 3-20 characters ka rakho — sirf letters, numbers, . ya _")
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(em).matches()) {
            fail("Email sahi se likho")
            return
        }
        if (password.length < 6) {
            fail("Password kam se kam 6 characters ka rakho")
            return
        }
        loading = true
        error = null
        info = null
        // Claim the username first: if the doc exists, the name is taken.
        db.collection("usernames").document(uname).get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    fail("Ye username pehle se liya gaya hai — koi dusra chuno")
                } else {
                    auth.createUserWithEmailAndPassword(em, password)
                        .addOnSuccessListener { result ->
                            val uid = result.user?.uid
                            runCatching {
                                result.user?.updateProfile(
                                    UserProfileChangeRequest.Builder()
                                        .setDisplayName(uname)
                                        .build()
                                )
                            }
                            if (uid != null) {
                                val batch = db.batch()
                                batch.set(
                                    db.collection("usernames").document(uname),
                                    mapOf("uid" to uid, "email" to em)
                                )
                                batch.set(
                                    db.collection("users").document(uid),
                                    mapOf("username" to uname, "email" to em)
                                )
                                batch.commit()
                                    .addOnCompleteListener { onLoggedIn() }
                            } else {
                                onLoggedIn()
                            }
                        }
                        .addOnFailureListener { e -> fail(authErrorMessage(e, createMode = true)) }
                }
            }
            .addOnFailureListener {
                fail("Server se connect nahi ho paya — internet check karke dobara try karo")
            }
    }

    fun doForgotPassword() {
        val em = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(em).matches()) {
            error = "Pehle upar apna email likho, phir Forgot password dabao"
            info = null
            return
        }
        loading = true
        error = null
        info = null
        auth.sendPasswordResetEmail(em)
            .addOnSuccessListener {
                loading = false
                info = "Password reset ka email bhej diya hai — apna inbox check karo"
            }
            .addOnFailureListener { e ->
                fail(authErrorMessage(e, createMode = false))
            }
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
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        singleLine = true,
                        enabled = !loading,
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
                    enabled = !loading,
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
                    enabled = !loading,
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
                        TextButton(onClick = { if (!loading) doForgotPassword() }) {
                            Text("Forgot password?", color = Gold, fontSize = 13.sp)
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }

                if (error != null) {
                    Text(
                        text = error!!,
                        color = ErrorRed,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (info != null) {
                    Text(
                        text = info!!,
                        color = InfoGray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }

                Button(
                    onClick = { if (createMode) doCreate() else doLogin() },
                    enabled = !loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gold,
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Text(
                            text = "  Please wait…",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text(
                            text = if (createMode) "Create Account" else "Log In",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
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
                    onClick = {
                        error = null
                        info = "Google login abhi taiyaar nahi hai — email se login karo"
                    },
                    enabled = !loading,
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
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (createMode) "Already have an account?" else "New here?",
                    color = Color(0xFFBDBDBD),
                    fontSize = 14.sp,
                )
                TextButton(onClick = {
                    createMode = !createMode
                    error = null
                    info = null
                }) {
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
