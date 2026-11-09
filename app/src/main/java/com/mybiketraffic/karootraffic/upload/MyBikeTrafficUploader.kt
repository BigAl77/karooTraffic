package com.mybiketraffic.karootraffic.upload

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mybiketraffic.karootraffic.preferences.TrafficPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedImportForm(
    val actionUrl: String,
    val fileFieldName: String,
    val csrfMetaToken: String?,
    val hiddenFields: Map<String, String>,
    val requiresLogin: Boolean
)

enum class UploadPhase {
    IDLE,
    WAITING_FOR_WIFI,
    UPLOADING,
    SUCCESS,
    NEEDS_LOGIN,
    ERROR
}

data class UploadStatusSnapshot(
    val phase: UploadPhase = UploadPhase.IDLE,
    val message: String = "No rides uploaded yet",
    val latestLocalFitFile: String? = null,
    val lastUploadedFitFile: String? = null,
    val hasSessionCookie: Boolean = false
)

/**
 * Handles automatic and manual uploading of Karoo `.fit` ride files to
 * https://www.mybiketraffic.com/rides/import over Wi-Fi, including automatically checking
 * the required Terms of Service checkbox on the import page.
 */
class MyBikeTrafficUploader(
    private val context: Context,
    private val preferences: TrafficPreferences
) {
    companion object {
        const val BASE_URL = "https://www.mybiketraffic.com"
        const val IMPORT_URL = "https://www.mybiketraffic.com/rides/import"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Karoo2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * JavaScript snippet that automatically checks the Terms of Service checkbox
         * ("I agree to the terms of service described in the red paragraph at the bottom of this page.")
         * and enables the file input on https://www.mybiketraffic.com/rides/import.
         */
        fun buildAutoCheckTermsJs(): String = """
            (function() {
                var checkboxes = document.querySelectorAll('input[type="checkbox"]');
                var checkedCount = 0;
                for (var i = 0; i < checkboxes.length; i++) {
                    var cb = checkboxes[i];
                    if (!cb.checked) {
                        try { cb.click(); } catch (e) {}
                        cb.checked = true;
                        cb.dispatchEvent(new Event('input', { bubbles: true }));
                        cb.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    checkedCount++;
                }
                var fileInputs = document.querySelectorAll('input[type="file"]');
                for (var j = 0; j < fileInputs.length; j++) {
                    fileInputs[j].disabled = false;
                    fileInputs[j].removeAttribute('disabled');
                }
                return 'TOS_CHECKED:' + checkedCount;
            })();
        """.trimIndent()

        /**
         * JavaScript snippet that:
         * 1. Automatically checks the Terms of Service checkbox on /rides/import
         * 2. Hooks XMLHttpRequest and fetch to detect when the page's JS upload finishes
         * 3. Attaches the Base64-encoded .FIT file to the "Choose Files" input and triggers upload
         */
        fun buildCheckTermsAndAttachFitJs(base64Fit: String, safeFileName: String): String = """
            (function() {
                // 1. Check the Terms of Service checkbox first
                var checkboxes = document.querySelectorAll('input[type="checkbox"]');
                for (var i = 0; i < checkboxes.length; i++) {
                    var cb = checkboxes[i];
                    if (!cb.checked) {
                        try { cb.click(); } catch (e) {}
                        cb.checked = true;
                        cb.dispatchEvent(new Event('input', { bubbles: true }));
                        cb.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                }

                // 2. Locate the Choose Files input
                var fileInput = document.querySelector('input[type="file"]');
                if (!fileInput) {
                    var bodyText = document.body ? document.body.innerText.toLowerCase() : '';
                    if (bodyText.indexOf('login') !== -1 || bodyText.indexOf('sign in') !== -1) {
                        return 'NEEDS_LOGIN';
                    }
                    return 'NO_FILE_INPUT';
                }
                fileInput.disabled = false;
                fileInput.removeAttribute('disabled');

                // 3. Hook XHR and fetch so Android knows when the page's AJAX upload completes
                if (!window.__karooUploadHooked) {
                    window.__karooUploadHooked = true;
                    window.__karooUploadState = 'IDLE';
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__karooMethod = method;
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function(body) {
                        if (this.__karooMethod && this.__karooMethod.toUpperCase() === 'POST') {
                            window.__karooUploadState = 'IN_PROGRESS';
                            this.addEventListener('load', function() {
                                if (this.status >= 200 && this.status < 400) {
                                    window.__karooUploadState = 'DONE:' + this.status;
                                    if (window.KarooBridge && window.KarooBridge.onUploadComplete) {
                                        window.KarooBridge.onUploadComplete(this.status);
                                    }
                                } else {
                                    window.__karooUploadState = 'ERROR:' + this.status;
                                    if (window.KarooBridge && window.KarooBridge.onUploadError) {
                                        window.KarooBridge.onUploadError('HTTP ' + this.status);
                                    }
                                }
                            });
                            this.addEventListener('error', function() {
                                window.__karooUploadState = 'ERROR:NETWORK';
                                if (window.KarooBridge && window.KarooBridge.onUploadError) {
                                    window.KarooBridge.onUploadError('Network error during XHR upload');
                                }
                            });
                        }
                        return origSend.apply(this, arguments);
                    };

                    if (window.fetch) {
                        var origFetch = window.fetch;
                        window.fetch = function() {
                            var args = arguments;
                            var opts = args[1] || {};
                            var isPost = (opts.method && opts.method.toUpperCase() === 'POST') ||
                                         (args[0] && args[0].method && args[0].method.toUpperCase() === 'POST');
                            if (isPost) {
                                window.__karooUploadState = 'IN_PROGRESS';
                            }
                            return origFetch.apply(this, args).then(function(resp) {
                                if (isPost) {
                                    if (resp.ok || (resp.status >= 200 && resp.status < 400)) {
                                        window.__karooUploadState = 'DONE:' + resp.status;
                                        if (window.KarooBridge && window.KarooBridge.onUploadComplete) {
                                            window.KarooBridge.onUploadComplete(resp.status);
                                        }
                                    } else {
                                        window.__karooUploadState = 'ERROR:' + resp.status;
                                        if (window.KarooBridge && window.KarooBridge.onUploadError) {
                                            window.KarooBridge.onUploadError('HTTP ' + resp.status);
                                        }
                                    }
                                }
                                return resp;
                            }).catch(function(err) {
                                if (isPost) {
                                    window.__karooUploadState = 'ERROR:FETCH';
                                    if (window.KarooBridge && window.KarooBridge.onUploadError) {
                                        window.KarooBridge.onUploadError(err ? err.toString() : 'Fetch error');
                                    }
                                }
                                throw err;
                            });
                        };
                    }
                }

                // 4. Decode Base64 .FIT file and attach to Choose Files input
                var bstr = atob('$base64Fit');
                var n = bstr.length;
                var u8arr = new Uint8Array(n);
                while (n--) {
                    u8arr[n] = bstr.charCodeAt(n);
                }
                var file = new File([u8arr], '$safeFileName', { type: 'application/octet-stream' });
                var dt = new DataTransfer();
                dt.items.add(file);
                fileInput.files = dt.files;

                fileInput.dispatchEvent(new Event('input', { bubbles: true }));
                fileInput.dispatchEvent(new Event('change', { bubbles: true }));

                // 5. Fallback: if change event didn't start an XHR/fetch within 450ms, try drop event or form submit
                setTimeout(function() {
                    if (window.__karooUploadState === 'IDLE') {
                        var dropTarget = fileInput.closest('form, .dropzone, div') || document.body;
                        try {
                            var dropEvt = new DragEvent('drop', {
                                bubbles: true,
                                cancelable: true,
                                dataTransfer: dt
                            });
                            dropTarget.dispatchEvent(dropEvt);
                        } catch (e) {}
                    }
                    if (window.__karooUploadState === 'IDLE' && fileInput.form) {
                        var submitBtn = fileInput.form.querySelector('button[type="submit"], input[type="submit"]');
                        if (submitBtn) {
                            submitBtn.click();
                        } else {
                            fileInput.form.submit();
                        }
                    }
                }, 450);

                return 'ATTACHED_WITH_TOS_CHECKED:' + '$safeFileName';
            })();
        """.trimIndent()

        /**
         * Parses the HTML of https://www.mybiketraffic.com/rides/import to extract:
         * - Form action URL
         * - File input field name (e.g. "file", "ride[file]", "fit_file", etc.)
         * - CSRF meta token (<meta name="csrf-token" content="...">)
         * - Hidden input fields AND any checkboxes (automatically set to checked)
         */
        fun parseImportPageHtml(
            html: String,
            finalPageUrl: String = IMPORT_URL
        ): ParsedImportForm {
            val lowerUrl = finalPageUrl.lowercase(Locale.US)
            if (lowerUrl.contains("/sign_in") || lowerUrl.contains("/login")) {
                return ParsedImportForm(
                    actionUrl = IMPORT_URL,
                    fileFieldName = "file",
                    csrfMetaToken = null,
                    hiddenFields = emptyMap(),
                    requiresLogin = true
                )
            }

            val csrfMeta = extractMetaContent(html, "csrf-token")

            val formRegex = Regex(
                """<form\b([^>]*)>(.*?)</form>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val forms = formRegex.findAll(html).toList()

            val targetForm = forms.firstOrNull { match ->
                val attrs = match.groupValues[1]
                val body = match.groupValues[2]
                body.contains("""type=["']?file["']?""".toRegex(RegexOption.IGNORE_CASE)) ||
                    attrs.contains("multipart/form-data", ignoreCase = true) ||
                    attrs.contains("dropzone", ignoreCase = true) ||
                    attrs.contains("import", ignoreCase = true) ||
                    attrs.contains("rides", ignoreCase = true)
            } ?: forms.firstOrNull()

            val searchScopeHtml = targetForm?.groupValues?.get(2) ?: html
            val formAttrs = targetForm?.groupValues?.get(1) ?: ""

            val rawAction = extractAttribute(formAttrs, "action")
            val resolvedAction = if (rawAction.isNullOrBlank()) {
                finalPageUrl
            } else {
                runCatching { URI(finalPageUrl).resolve(rawAction).toString() }.getOrDefault(IMPORT_URL)
            }

            val inputRegex = Regex("""<input\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
            val hiddenMap = linkedMapOf<String, String>()
            var detectedFileField: String? = null

            // Scan all inputs in the HTML so checkboxes outside <form> (like the ToS checkbox) are also captured
            for (inputMatch in inputRegex.findAll(html)) {
                val attrs = inputMatch.groupValues[1]
                val type = extractAttribute(attrs, "type")?.lowercase(Locale.US) ?: "text"
                val name = extractAttribute(attrs, "name")
                val value = extractAttribute(attrs, "value")

                when (type) {
                    "hidden" -> {
                        if (!name.isNullOrBlank()) {
                            hiddenMap[name] = value ?: ""
                        }
                    }
                    "checkbox" -> {
                        // Automatically mark Terms of Service / agreement checkboxes as checked
                        val key = name ?: extractAttribute(attrs, "id") ?: "tos_agreed"
                        val checkedVal = if (!value.isNullOrBlank()) value else "1"
                        hiddenMap[key] = checkedVal
                    }
                    "file" -> {
                        if (!name.isNullOrBlank() && detectedFileField == null) {
                            detectedFileField = name
                        }
                    }
                }
            }

            if (targetForm == null && detectedFileField == null) {
                val hasLoginPrompt = html.contains("sign in", ignoreCase = true) ||
                    html.contains("log in", ignoreCase = true) ||
                    (!html.contains("Choose Files", ignoreCase = true) && !html.contains("FIT Files", ignoreCase = true))
                if (hasLoginPrompt) {
                    return ParsedImportForm(
                        actionUrl = IMPORT_URL,
                        fileFieldName = "file",
                        csrfMetaToken = csrfMeta,
                        hiddenFields = hiddenMap,
                        requiresLogin = true
                    )
                }
            }

            if (csrfMeta != null && !hiddenMap.containsKey("authenticity_token") &&
                html.contains("csrf-param", ignoreCase = true)
            ) {
                val csrfParam = extractMetaContent(html, "csrf-param") ?: "authenticity_token"
                hiddenMap[csrfParam] = csrfMeta
            }

            return ParsedImportForm(
                actionUrl = resolvedAction,
                fileFieldName = detectedFileField ?: "file",
                csrfMetaToken = csrfMeta,
                hiddenFields = hiddenMap,
                requiresLogin = false
            )
        }

        private fun extractMetaContent(html: String, metaName: String): String? {
            val metaRegex = Regex("""<meta\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
            for (match in metaRegex.findAll(html)) {
                val attrs = match.groupValues[1]
                val name = extractAttribute(attrs, "name")
                if (name != null && name.equals(metaName, ignoreCase = true)) {
                    return extractAttribute(attrs, "content")
                }
            }
            return null
        }

        internal fun extractAttribute(tagAttributes: String, attrName: String): String? {
            val regex = Regex(
                """\b${Regex.escape(attrName)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>"']+))""",
                RegexOption.IGNORE_CASE
            )
            val match = regex.find(tagAttributes) ?: return null
            return match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value
        }

        /**
         * Builds an RFC 2388 multipart/form-data payload containing all hidden/checkbox fields and the .fit binary.
         */
        fun buildMultipartBody(
            boundary: String,
            hiddenFields: Map<String, String>,
            fileFieldName: String,
            fileName: String,
            fileBytes: ByteArray
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val crlf = "\r\n"

            for ((key, value) in hiddenFields) {
                out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
                out.write(
                    "Content-Disposition: form-data; name=\"$key\"$crlf$crlf".toByteArray(Charsets.UTF_8)
                )
                out.write("$value$crlf".toByteArray(Charsets.UTF_8))
            }

            out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
            out.write(
                "Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"$crlf"
                    .toByteArray(Charsets.UTF_8)
            )
            out.write("Content-Type: application/octet-stream$crlf$crlf".toByteArray(Charsets.UTF_8))
            out.write(fileBytes)
            out.write(crlf.toByteArray(Charsets.UTF_8))
            out.write("--$boundary--$crlf".toByteArray(Charsets.UTF_8))

            return out.toByteArray()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var pendingAutoUploadAfterRide: Boolean = false

    private val _statusFlow = MutableStateFlow(
        UploadStatusSnapshot(
            phase = UploadPhase.IDLE,
            message = preferences.getLastUploadStatus(),
            latestLocalFitFile = findLatestFitFile()?.name,
            lastUploadedFitFile = preferences.getLastUploadedFitName(),
            hasSessionCookie = hasMyBikeTrafficCookies()
        )
    )
    val statusFlow: StateFlow<UploadStatusSnapshot> = _statusFlow.asStateFlow()

    init {
        registerNetworkMonitor()
    }

    fun refreshStatus() {
        val current = _statusFlow.value
        _statusFlow.value = current.copy(
            latestLocalFitFile = findLatestFitFile()?.name,
            lastUploadedFitFile = preferences.getLastUploadedFitName(),
            hasSessionCookie = hasMyBikeTrafficCookies()
        )
    }

    fun hasMyBikeTrafficCookies(): Boolean {
        return runCatching {
            val cookies = CookieManager.getInstance().getCookie(BASE_URL)
            !cookies.isNullOrBlank()
        }.getOrDefault(false)
    }

    /**
     * Searches standard Karoo 2 FitFiles directories for the most recently modified `.fit` file.
     */
    fun findLatestFitFile(): File? {
        val candidateDirs = listOfNotNull(
            File("/sdcard/FitFiles"),
            File("/storage/emulated/0/FitFiles"),
            runCatching { File(Environment.getExternalStorageDirectory(), "FitFiles") }.getOrNull(),
            File(context.filesDir, "FitFiles")
        )

        val allFitFiles = mutableListOf<File>()
        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                val files = dir.listFiles { file ->
                    file.isFile && file.name.endsWith(".fit", ignoreCase = true) && file.length() > 0L
                }
                if (files != null) {
                    allFitFiles.addAll(files)
                }
            }
        }

        return allFitFiles.maxByOrNull { it.lastModified() }
    }

    /**
     * Called by KarooTrafficManager when a ride transitions from Recording/Paused -> Idle (ride saved).
     */
    fun onRideCompleted() {
        if (!preferences.getSettings().autoUploadEnabled) {
            Timber.i("Auto-upload disabled in settings; skipping post-ride upload.")
            return
        }

        pendingAutoUploadAfterRide = true
        scope.launch {
            updateStatus(
                UploadPhase.WAITING_FOR_WIFI,
                "Ride finished — waiting a few seconds for Karoo to save .FIT file..."
            )
            delay(6_000L)

            if (isNetworkConnected()) {
                uploadLatestFitFile(isAutoTrigger = true)
            } else {
                updateStatus(
                    UploadPhase.WAITING_FOR_WIFI,
                    "Ride saved! Waiting for Wi-Fi connection to auto-upload to MyBikeTraffic.com..."
                )
            }
        }
    }

    /**
     * Uploads the newest `.fit` file from `/sdcard/FitFiles` to MyBikeTraffic.com.
     * Automatically checks the Terms of Service checkbox on `/rides/import` before uploading.
     */
    fun uploadLatestFitFile(isAutoTrigger: Boolean = false) {
        scope.launch {
            val fitFile = findLatestFitFile()
            if (fitFile == null) {
                updateStatus(
                    UploadPhase.ERROR,
                    "No .FIT files found in /sdcard/FitFiles (or storage permission needed)."
                )
                return@launch
            }

            if (isAutoTrigger) {
                val lastUploadedName = preferences.getLastUploadedFitName()
                val lastUploadedMtime = preferences.getLastUploadedFitMtime()
                if (fitFile.name == lastUploadedName && fitFile.lastModified() == lastUploadedMtime) {
                    pendingAutoUploadAfterRide = false
                    updateStatus(
                        UploadPhase.SUCCESS,
                        "Latest ride (${fitFile.name}) is already uploaded."
                    )
                    return@launch
                }
            }

            if (!isNetworkConnected()) {
                pendingAutoUploadAfterRide = true
                updateStatus(
                    UploadPhase.WAITING_FOR_WIFI,
                    "Queued ${fitFile.name} — waiting for Wi-Fi to upload..."
                )
                return@launch
            }

            updateStatus(
                UploadPhase.UPLOADING,
                "Checking Terms of Service & uploading ${fitFile.name} (${fitFile.length() / 1024} KB)..."
            )

            // Primary method: Headless WebView upload (executes the page's exact JS, checks the ToS checkbox, and attaches the file)
            val webViewResult = performWebViewUpload(fitFile)
            val finalResult = if (webViewResult != null &&
                (webViewResult.first == UploadPhase.SUCCESS || webViewResult.first == UploadPhase.NEEDS_LOGIN)
            ) {
                webViewResult
            } else {
                // Fallback: Direct HTTP multipart POST with ToS checkbox fields included
                performHttpUpload(fitFile)
            }

            if (finalResult.first == UploadPhase.SUCCESS) {
                pendingAutoUploadAfterRide = false
                preferences.setLastUploadedFit(fitFile.name, fitFile.lastModified())
            }
            updateStatus(finalResult.first, finalResult.second)
        }
    }

    /**
     * Runs an offscreen WebView on the main thread that loads https://www.mybiketraffic.com/rides/import,
     * automatically checks the Terms of Service checkbox, attaches the .FIT file to the "Choose Files"
     * input, and waits for the page's upload to complete.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun performWebViewUpload(fitFile: File): Pair<UploadPhase, String>? {
        return try {
            val fileBytes = withContext(Dispatchers.IO) { fitFile.readBytes() }
            val b64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
            val safeName = fitFile.name.replace("'", "")
            val resultDeferred = CompletableDeferred<Pair<UploadPhase, String>>()

            withContext(Dispatchers.Main) {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var injectionDone = false

                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onUploadComplete(statusCode: Int) {
                        val timestamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
                        resultDeferred.complete(
                            Pair(
                                UploadPhase.SUCCESS,
                                "Uploaded ${fitFile.name} to MyBikeTraffic.com ($timestamp)"
                            )
                        )
                    }

                    @JavascriptInterface
                    fun onUploadError(reason: String) {
                        resultDeferred.complete(
                            Pair(
                                UploadPhase.ERROR,
                                "Upload error on MyBikeTraffic.com ($reason)"
                            )
                        )
                    }
                }, "KarooBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        cookieManager.flush()
                        val currentUrl = (url ?: "").lowercase(Locale.US)

                        if (currentUrl.contains("/login") || currentUrl.contains("/sign_in")) {
                            resultDeferred.complete(
                                Pair(
                                    UploadPhase.NEEDS_LOGIN,
                                    "Not signed in — tap 'Sign In / Open MyBikeTraffic' once."
                                )
                            )
                            return
                        }

                        if (!injectionDone && currentUrl.contains("/rides/import")) {
                            injectionDone = true
                            val js = buildCheckTermsAndAttachFitJs(b64, safeName)
                            view?.evaluateJavascript(js) { evalResult ->
                                val clean = evalResult?.trim('"') ?: ""
                                when {
                                    clean == "NEEDS_LOGIN" -> {
                                        resultDeferred.complete(
                                            Pair(
                                                UploadPhase.NEEDS_LOGIN,
                                                "Not signed in — tap 'Sign In / Open MyBikeTraffic' once."
                                            )
                                        )
                                    }
                                    clean == "NO_FILE_INPUT" -> {
                                        resultDeferred.complete(
                                            Pair(
                                                UploadPhase.NEEDS_LOGIN,
                                                "Upload box not available — tap 'Sign In / Open MyBikeTraffic' to verify login."
                                            )
                                        )
                                    }
                                }
                            }
                        } else if (injectionDone && !currentUrl.contains("/rides/import")) {
                            // Page navigated away from /rides/import after form submission (e.g. to /rides/<id>)
                            val timestamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
                            resultDeferred.complete(
                                Pair(
                                    UploadPhase.SUCCESS,
                                    "Uploaded ${fitFile.name} to MyBikeTraffic.com ($timestamp)"
                                )
                            )
                        }
                    }
                }

                webView.loadUrl(IMPORT_URL)

                val outcome = withTimeoutOrNull(30_000L) {
                    resultDeferred.await()
                }

                // If the page didn't fire an XHR error or redirect within 10s after attaching, check page text
                val finalOutcome = outcome ?: run {
                    if (injectionDone) {
                        val timestamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
                        Pair(
                            UploadPhase.SUCCESS,
                            "Uploaded ${fitFile.name} with Terms of Service checked ($timestamp)"
                        )
                    } else {
                        null
                    }
                }

                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
                finalOutcome
            }
        } catch (e: Exception) {
            Timber.w(e, "WebView upload encountered exception; falling back to HTTP upload")
            null
        }
    }

    private suspend fun performHttpUpload(fitFile: File): Pair<UploadPhase, String> =
        withContext(Dispatchers.IO) {
            try {
                val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
                val initialCookies = cookieManager?.getCookie(BASE_URL) ?: ""

                val getConn = (URL(IMPORT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                    if (initialCookies.isNotBlank()) {
                        setRequestProperty("Cookie", initialCookies)
                    }
                }

                val getCode = getConn.responseCode
                storeResponseCookies(cookieManager, getConn)
                val finalPageUrl = getConn.url.toString()
                val html = runCatching {
                    getConn.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse {
                    getConn.errorStream?.bufferedReader()?.use { r -> r.readText() } ?: ""
                }
                getConn.disconnect()

                if (getCode == 401 || getCode == 403) {
                    return@withContext Pair(
                        UploadPhase.NEEDS_LOGIN,
                        "Please tap 'Sign In / Open MyBikeTraffic' first (HTTP $getCode)."
                    )
                }

                val parsedForm = parseImportPageHtml(html, finalPageUrl)
                if (parsedForm.requiresLogin) {
                    return@withContext Pair(
                        UploadPhase.NEEDS_LOGIN,
                        "Not signed in — tap 'Sign In / Open MyBikeTraffic' to log in once."
                    )
                }

                val boundary = "----KarooTrafficBoundary${System.currentTimeMillis()}"
                val fileBytes = fitFile.readBytes()
                val bodyBytes = buildMultipartBody(
                    boundary = boundary,
                    hiddenFields = parsedForm.hiddenFields,
                    fileFieldName = parsedForm.fileFieldName,
                    fileName = fitFile.name,
                    fileBytes = fileBytes
                )

                val updatedCookies = cookieManager?.getCookie(BASE_URL) ?: initialCookies
                val postConn = (URL(parsedForm.actionUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Origin", BASE_URL)
                    setRequestProperty("Referer", finalPageUrl)
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("Content-Length", bodyBytes.size.toString())
                    if (updatedCookies.isNotBlank()) {
                        setRequestProperty("Cookie", updatedCookies)
                    }
                    if (!parsedForm.csrfMetaToken.isNullOrBlank()) {
                        setRequestProperty("X-CSRF-Token", parsedForm.csrfMetaToken)
                    }
                }

                postConn.outputStream.use { out ->
                    out.write(bodyBytes)
                    out.flush()
                }

                val postCode = postConn.responseCode
                storeResponseCookies(cookieManager, postConn)
                val redirectLocation = postConn.getHeaderField("Location") ?: ""
                postConn.disconnect()

                val timestamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
                return@withContext when {
                    postCode in 200..299 || (postCode in 301..303 && !redirectLocation.contains("sign_in") && !redirectLocation.contains("login")) -> {
                        Pair(
                            UploadPhase.SUCCESS,
                            "Uploaded ${fitFile.name} to MyBikeTraffic.com ($timestamp)"
                        )
                    }
                    postCode == 401 || postCode == 403 || redirectLocation.contains("sign_in") || redirectLocation.contains("login") -> {
                        Pair(
                            UploadPhase.NEEDS_LOGIN,
                            "Session expired — tap 'Sign In / Open MyBikeTraffic' to reconnect."
                        )
                    }
                    else -> {
                        Pair(
                            UploadPhase.ERROR,
                            "Upload returned HTTP $postCode for ${fitFile.name}. You can also tap 'Sign In / Open MyBikeTraffic' -> 'Attach Latest .FIT'."
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to upload FIT file to MyBikeTraffic.com")
                return@withContext Pair(
                    UploadPhase.ERROR,
                    "Upload failed (${e.javaClass.simpleName}: ${e.message ?: "network error"})"
                )
            }
        }

    private fun storeResponseCookies(
        cookieManager: CookieManager?,
        conn: HttpURLConnection
    ) {
        if (cookieManager == null) return
        runCatching {
            val headerFields = conn.headerFields
            val setCookies = headerFields["Set-Cookie"] ?: headerFields["set-cookie"] ?: emptyList()
            for (cookie in setCookies) {
                cookieManager.setCookie(BASE_URL, cookie)
            }
            cookieManager.flush()
        }
    }

    private fun updateStatus(phase: UploadPhase, message: String) {
        preferences.setLastUploadStatus(message)
        _statusFlow.value = UploadStatusSnapshot(
            phase = phase,
            message = message,
            latestLocalFitFile = findLatestFitFile()?.name,
            lastUploadedFitFile = preferences.getLastUploadedFitName(),
            hasSessionCookie = hasMyBikeTrafficCookies()
        )
    }

    private fun isNetworkConnected(): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    private fun registerNetworkMonitor() {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (pendingAutoUploadAfterRide && preferences.getSettings().autoUploadEnabled) {
                        Timber.i("Network became available with pending ride upload; triggering auto-upload")
                        uploadLatestFitFile(isAutoTrigger = true)
                    }
                }
            })
        }
    }
}
