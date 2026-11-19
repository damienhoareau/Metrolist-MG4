/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.lyrics.SpotifyLyricsApi
import com.metrolist.music.lyrics.SpotifySessionCookieStore
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.delay
import timber.log.Timber

private const val SPOTIFY_LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
private val SPOTIFY_COOKIE_URLS = listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://spotify.com")

/**
 * Lets the user sign in to Spotify in a WebView and keeps only the `sp_dc` session cookie, encrypted by
 * [SpotifySessionCookieStore]. The WebView's Spotify cookies are removed once it is read, so the session
 * is not stored twice.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginScreen(navController: NavController) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var linked by remember { mutableStateOf(false) }

    fun captureSessionCookie() {
        if (linked) return
        val cookie = readSpotifySessionCookie() ?: return
        runCatching { SpotifySessionCookieStore.store(context, cookie) }
            .onSuccess {
                linked = true
                SpotifyLyricsApi.clearSession()
                clearSpotifyCookies()
                Timber.tag("SpotifyLogin").i("Spotify session cookie captured")
                navController.navigateUp()
            }.onFailure { Timber.tag("SpotifyLogin").w(it, "Spotify session cookie not stored") }
    }

    // Sign-in steps that do not load a new page (code entry, for instance) are caught by this check.
    LaunchedEffect(Unit) {
        while (!linked) {
            delay(1_000)
            captureSessionCookie()
        }
    }

    DisposableEffect(Unit) {
        onDispose { webViewRef?.destroy() }
    }

    // The insets already reserve the top bar, which is drawn over them like on the other screens.
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier =
                Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                    .fillMaxSize(),
            factory = { webViewContext ->
                WebView(webViewContext).apply {
                    // With the default wrap_content height Chromium sizes the viewport to 0 (100vh = 0),
                    // which collapses Spotify's full-height layout.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = request.url.scheme != "http" && request.url.scheme != "https"

                            override fun onPageFinished(
                                view: WebView,
                                url: String?,
                            ) = captureSessionCookie()
                        }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // One CSS pixel per screen pixel: at the head unit's density the page is otherwise too short
                    // for Spotify's layout, which leaves only a sliver for the sign-in form.
                    setInitialScale(100)
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewRef = this
                    loadUrl(SPOTIFY_LOGIN_URL)
                }
            },
        )

        TopAppBar(
            title = { Text(stringResource(R.string.spotify_login)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }
}

private fun readSpotifySessionCookie(): String? =
    CookieManager.getInstance().getCookie("https://open.spotify.com")
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("sp_dc=") }
        ?.removePrefix("sp_dc=")
        ?.takeIf { it.isNotBlank() }

private fun clearSpotifyCookies() {
    val cookieManager = CookieManager.getInstance()
    SPOTIFY_COOKIE_URLS.forEach { url ->
        cookieManager.getCookie(url)
            ?.split(';')
            ?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
            ?.forEach { name ->
                // A cookie is only replaced by one with the same domain; __Host- cookies also need Secure.
                listOf("", "; Domain=.${Uri.parse(url).host}", "; Domain=.spotify.com").forEach { domain ->
                    cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure$domain")
                }
            }
    }
    cookieManager.flush()
}
