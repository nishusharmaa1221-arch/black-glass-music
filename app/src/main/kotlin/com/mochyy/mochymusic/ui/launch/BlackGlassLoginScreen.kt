// BlackGlassLoginScreen.kt
//
// Musify login gate — REAL accounts backed by Firebase Authentication.
// - Sign up is email + password + confirm password ONLY (the owner removed
//   the username field from this form on 2026-11-13). One account per email:
//   Firebase itself rejects a second signup with the same email
//   (FirebaseAuthUserCollisionException).
// - The username lives on the app's own first-run profile setup ("Let's
//   create your profile!") instead, where it is set once and can be edited
//   later from the profile page.
// - Login only succeeds with the correct email + password.
// The session is Firebase's own persisted session: once signed in, the gate
// stays open across restarts until the user signs out.

package com.mochyy.mochymusic.ui.launch

import com.mochyy.mochymusic.R

import android.content.Context
import android.util.Patterns
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONObject
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException

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


// ---- Firestore over REST (no Firestore SDK — see build.gradle.kts note) ----

private const val FirestoreBase =
    "https://firestore.googleapis.com/v1/projects/musify-f5f88/databases/(default)/documents"

private fun String.jsonEsc(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun firestoreRequest(method: String, urlStr: String, token: String?, body: String?): Int {
    val conn = URL(urlStr).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn.responseCode
    } finally {
        conn.disconnect()
    }
}

private fun firestoreGetBody(urlStr: String, token: String?): Pair<Int, String> {
    val conn = URL(urlStr).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    } finally {
        conn.disconnect()
    }
}

// ---- Moderation enforcement (v1.2.0) ----
// The admin panel sets moderation fields on the user's own users/{uid}
// record: status ("banned" | "suspended"), suspendedUntil, deleteRequested.
// After a successful sign-in AND on a restored session, the app reads its
// own record over Firestore REST and honours those fields BEFORE entering:
// - deleteRequested -> delete own record, then delete own Auth account
//   (Identity Toolkit accounts:delete with the user's own ID token),
//   sign out, message "Aapka account delete kar diya gaya hai."
// - banned -> sign out, "Ye account ban kar diya gaya hai."
// - suspended with suspendedUntil in the future -> sign out,
//   "Aapka account <date IST> tak suspend hai." A lapsed date = active.
// FAIL-OPEN by design: missing doc, missing fields or ANY error = allowed,
// so a failed check never disturbs existing users.
object BlackGlassModeration {

    sealed interface Result {
        data object Allowed : Result
        data class Blocked(val message: String) : Result
    }

    fun check(user: com.google.firebase.auth.FirebaseUser, onResult: (Result) -> Unit) {
        Thread {
            val result = runCatching { checkSync(user) }.getOrElse { Result.Allowed }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun checkSync(user: com.google.firebase.auth.FirebaseUser): Result {
        val token = runCatching { Tasks.await(user.getIdToken(false)).token }.getOrNull()
            ?: return Result.Allowed
        val uid = user.uid
        val (code, text) = firestoreGetBody("$FirestoreBase/users/$uid", token)
        if (code != 200) return Result.Allowed
        val fields = runCatching { JSONObject(text).getJSONObject("fields") }.getOrNull()
            ?: return Result.Allowed

        val deleteRequested =
            fields.optJSONObject("deleteRequested")?.optBoolean("booleanValue") == true
        if (deleteRequested) {
            runCatching { firestoreRequest("DELETE", "$FirestoreBase/users/$uid", token, null) }
            runCatching {
                val apiKey = com.google.firebase.FirebaseApp.getInstance().options.apiKey
                val body = JSONObject().put("idToken", token).toString()
                firestoreRequest(
                    "POST",
                    "https://identitytoolkit.googleapis.com/v1/accounts:delete?key=$apiKey",
                    null,
                    body,
                )
            }
            BlackGlassAuth.signOut()
            return Result.Blocked("Aapka account delete kar diya gaya hai.")
        }

        val status = fields.optJSONObject("status")?.optString("stringValue")
        if (status == "banned") {
            BlackGlassAuth.signOut()
            return Result.Blocked("Ye account ban kar diya gaya hai.")
        }
        if (status == "suspended") {
            val untilStr = fields.optJSONObject("suspendedUntil")?.optString("timestampValue")
            val until = untilStr?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (until != null && until.isAfter(Instant.now())) {
                BlackGlassAuth.signOut()
                val dateStr = java.time.format.DateTimeFormatter
                    .ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
                    .withZone(java.time.ZoneId.of("Asia/Kolkata"))
                    .format(until)
                return Result.Blocked("Aapka account $dateStr tak suspend hai.")
            }
        }
        return Result.Allowed
    }
}

@Composable
fun BlackGlassLoginScreen(
    onLoggedIn: () -> Unit,
    notice: String? = null,
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }

    var createMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var confirmVisible by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(notice) }
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
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    onLoggedIn()
                    return@addOnSuccessListener
                }
                // Moderation check before entering (v1.2.0) — fail-open.
                BlackGlassModeration.check(user) { res ->
                    when (res) {
                        is BlackGlassModeration.Result.Allowed -> onLoggedIn()
                        is BlackGlassModeration.Result.Blocked -> fail(res.message)
                    }
                }
            }
            .addOnFailureListener { e -> fail(authErrorMessage(e, createMode = false)) }
    }

    fun doCreate() {
        val em = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(em).matches()) {
            fail("Email sahi se likho")
            return
        }
        if (password.length < 6) {
            fail("Password kam se kam 6 characters ka rakho")
            return
        }
        if (confirmPassword != password) {
            fail("Confirm password match nahi ho raha — dono jagah ek hi password likho")
            return
        }
        loading = true
        error = null
        info = null
        auth.createUserWithEmailAndPassword(em, password)
            .addOnSuccessListener { result ->
                val user = result.user
                val uid = user?.uid
                if (user == null || uid == null) {
                    onLoggedIn()
                    return@addOnSuccessListener
                }
                // save a small profile record (email + createdAt) — best effort,
                // the account itself is already created either way
                user.getIdToken(false)
                    .addOnSuccessListener { tokenResult ->
                        Thread {
                            val now = Instant.now().toString()
                            val userBody =
                                "{\"fields\":{\"email\":{\"stringValue\":\"" + em.jsonEsc() +
                                    "\"},\"createdAt\":{\"timestampValue\":\"" + now + "\"}}}"
                            runCatching {
                                firestoreRequest(
                                    "POST",
                                    "$FirestoreBase/users?documentId=$uid",
                                    tokenResult.token,
                                    userBody
                                )
                            }
                            Handler(Looper.getMainLooper()).post { onLoggedIn() }
                        }.start()
                    }
                    .addOnFailureListener { onLoggedIn() }
            }
            .addOnFailureListener { e -> fail(authErrorMessage(e, createMode = true)) }
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
            // Gold ring logo — gradient gold ring, the app M inside
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x2ED4AF37), Color.Transparent),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(
                            2.dp,
                            Brush.sweepGradient(
                                listOf(
                                    Gold,
                                    Color(0xFF7A5E14),
                                    Color(0xFFF3D27A),
                                    Gold,
                                )
                            ),
                            CircleShape,
                        )
                        .background(Color(0x14D4AF37)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_m),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Musify",
                color = Color.White,
                fontSize = 30.sp,
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
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(Color(0x73D4AF37), GlassBorder, Color(0x73D4AF37))
                        ),
                        RoundedCornerShape(24.dp),
                    )
                    .padding(20.dp),
            ) {
                Text(
                    text = if (createMode) "Create your account" else "Welcome back",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (createMode) "Sign up karke apni music journey shuru karo" else "Login karo aur music sunte raho",
                    color = Color(0xFFBDBDBD),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
                )
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

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(14.dp),
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
                    shape = RoundedCornerShape(14.dp),
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

                if (createMode) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password") },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        singleLine = true,
                        enabled = !loading,
                        visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = {
                            IconButton(onClick = { confirmVisible = !confirmVisible }) {
                                Icon(
                                    imageVector = if (confirmVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (confirmVisible) "Hide password" else "Show password",
                                    tint = Color(0xFFBDBDBD),
                                )
                            }
                        },
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

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

                Box(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            error = null
                            info = "Google login jald aa raha hai — abhi email se login karo"
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
                        Icon(
                            painter = painterResource(R.drawable.ic_google_g),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "  Continue with Google",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    // COMING SOON tag on the button's top-right corner
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-10).dp, y = (-7).dp)
                            .zIndex(1f)
                            .clip(RoundedCornerShape(50))
                            .background(Gold)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "COMING SOON",
                            color = Color.Black,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
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
