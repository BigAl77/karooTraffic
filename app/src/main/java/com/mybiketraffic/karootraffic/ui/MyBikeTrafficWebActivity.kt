package com.mybiketraffic.karootraffic.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mybiketraffic.karootraffic.karoo.KarooTrafficManager
import com.mybiketraffic.karootraffic.upload.MyBikeTrafficUploader

/**
 * In-app browser for signing into https://www.mybiketraffic.com once (persisting session cookies
 * for automatic post-ride background uploads) and providing 1-tap .FIT file attachment with
 * automatic Terms of Service checkbox checking.
 */
class MyBikeTrafficWebActivity : AppCompatActivity() {

    private lateinit var manager: KarooTrafficManager
    private lateinit var webView: WebView
    private lateinit var statusBanner: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = KarooTrafficManager.getInstance(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12161F"))
        }

        // Top Navigation & Action Bar (designed for Karoo 2's 480x800 screen)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E2433"))
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
        }

        val closeBtn = toolbarButton("Done / Back", "#374151") {
            CookieManager.getInstance().flush()
            manager.uploader.refreshStatus()
            finish()
        }

        val importPageBtn = toolbarButton("Import Page", "#2563EB") {
            webView.loadUrl(MyBikeTrafficUploader.IMPORT_URL)
        }

        val autoAttachBtn = toolbarButton("Attach Latest .FIT", "#059669") {
            injectLatestFitFileIntoPage()
        }

        topBar.addView(closeBtn)
        topBar.addView(importPageBtn)
        topBar.addView(autoAttachBtn)
        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        statusBanner = TextView(this).apply {
            text = "Sign in below once — Terms of Service checkbox will be checked automatically."
            setTextColor(Color.parseColor("#A7F3D0"))
            setBackgroundColor(Color.parseColor("#172030"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            statusBanner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cookieManager.flush()
                manager.uploader.refreshStatus()

                // Automatically check the Terms of Service checkbox as soon as the page loads
                view?.evaluateJavascript(MyBikeTrafficUploader.buildAutoCheckTermsJs(), null)

                val latestFit = manager.uploader.findLatestFitFile()
                statusBanner.text = if (latestFit != null) {
                    "Terms checkbox checked. Latest ride ready: ${latestFit.name} (tap 'Choose Files' or 'Attach Latest .FIT')"
                } else {
                    "Terms checkbox checked. Session saved for: ${url ?: MyBikeTrafficUploader.IMPORT_URL}"
                }
            }
        }

        // Automatically supply the newest .fit file from /sdcard/FitFiles when the webpage opens a file chooser!
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Ensure the Terms of Service checkbox is checked before returning the file to the page
                webView?.evaluateJavascript(MyBikeTrafficUploader.buildAutoCheckTermsJs(), null)

                val latestFit = manager.uploader.findLatestFitFile()
                if (latestFit != null) {
                    Toast.makeText(
                        this@MyBikeTrafficWebActivity,
                        "Auto-selected newest ride: ${latestFit.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                    filePathCallback?.onReceiveValue(arrayOf(Uri.fromFile(latestFit)))
                    return true
                } else {
                    Toast.makeText(
                        this@MyBikeTrafficWebActivity,
                        "No .FIT files found in /sdcard/FitFiles yet",
                        Toast.LENGTH_SHORT
                    ).show()
                    filePathCallback?.onReceiveValue(null)
                    return true
                }
            }
        }

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        webView.loadUrl(MyBikeTrafficUploader.IMPORT_URL)
    }

    /**
     * Automatically checks the Terms of Service checkbox and attaches the newest local .FIT file
     * into the page's "Choose Files" <input type="file">.
     */
    private fun injectLatestFitFileIntoPage() {
        val latestFit = manager.uploader.findLatestFitFile()
        if (latestFit == null) {
            Toast.makeText(this, "No .FIT file found in /sdcard/FitFiles", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val bytes = latestFit.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val safeName = latestFit.name.replace("'", "")
            val js = MyBikeTrafficUploader.buildCheckTermsAndAttachFitJs(b64, safeName)

            webView.evaluateJavascript(js) { result ->
                statusBanner.text = "Checked Terms & attached ${latestFit.name} ($result)"
                Toast.makeText(this, "Checked Terms & attached ${latestFit.name}", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { e ->
            Toast.makeText(this, "Error reading .FIT: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        manager.uploader.refreshStatus()
    }

    private fun toolbarButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = dp(6).toFloat()
            }
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
