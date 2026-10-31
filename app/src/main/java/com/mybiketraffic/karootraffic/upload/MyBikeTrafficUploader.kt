package com.mybiketraffic.karootraffic.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import android.webkit.CookieManager
import com.mybiketraffic.karootraffic.preferences.TrafficPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * https://www.mybiketraffic.com/rides/import over Wi-Fi.
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
         * Parses the HTML of https://www.mybiketraffic.com/rides/import to extract:
         * - Form action URL
         * - File input field name (e.g. "file", "ride[file]", "fit_file", etc.)
         * - CSRF meta token (<meta name="csrf-token" content="...">)
         * - Hidden input fields (e.g. authenticity_token)
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

            // Find all <form ...>...</form> blocks and pick the one with file upload or import action
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

            if (targetForm == null) {
                val hasLoginPrompt = html.contains("sign in", ignoreCase = true) ||
                    html.contains("log in", ignoreCase = true)
                return ParsedImportForm(
                    actionUrl = IMPORT_URL,
                    fileFieldName = "file",
                    csrfMetaToken = csrfMeta,
                    hiddenFields = emptyMap(),
                    requiresLogin = hasLoginPrompt
                )
            }

            val formAttrs = targetForm.groupValues[1]
            val formBody = targetForm.groupValues[2]

            val rawAction = extractAttribute(formAttrs, "action")
            val resolvedAction = if (rawAction.isNullOrBlank()) {
                finalPageUrl
            } else {
                runCatching { URI(finalPageUrl).resolve(rawAction).toString() }.getOrDefault(IMPORT_URL)
            }

            val inputRegex = Regex("""<input\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
            val hiddenMap = linkedMapOf<String, String>()
            var detectedFileField: String? = null

            for (inputMatch in inputRegex.findAll(formBody)) {
                val attrs = inputMatch.groupValues[1]
                val type = extractAttribute(attrs, "type")?.lowercase(Locale.US) ?: "text"
                val name = extractAttribute(attrs, "name")
                val value = extractAttribute(attrs, "value") ?: ""

                when (type) {
                    "hidden" -> {
                        if (!name.isNullOrBlank()) {
                            hiddenMap[name] = value
                        }
                    }
                    "file" -> {
                        if (!name.isNullOrBlank() && detectedFileField == null) {
                            detectedFileField = name
                        }
                    }
                }
            }

            // If a CSRF meta token exists and authenticity_token wasn't in hidden inputs, include it
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
         * Builds an RFC 2388 multipart/form-data payload containing all hidden fields and the .fit binary.
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
            // Give Hammerhead Karoo OS ~6 seconds to finish writing the .fit file to /sdcard/FitFiles
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
                "Uploading ${fitFile.name} (${fitFile.length() / 1024} KB) to MyBikeTraffic.com..."
            )

            val result = performHttpUpload(fitFile)
            if (result.first == UploadPhase.SUCCESS) {
                pendingAutoUploadAfterRide = false
                preferences.setLastUploadedFit(fitFile.name, fitFile.lastModified())
            }
            updateStatus(result.first, result.second)
        }
    }

    private suspend fun performHttpUpload(fitFile: File): Pair<UploadPhase, String> =
        withContext(Dispatchers.IO) {
            try {
                val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
                val initialCookies = cookieManager?.getCookie(BASE_URL) ?: ""

                // Step 1: GET /rides/import to fetch form action, CSRF token, and verify session
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
                        "Please tap 'Sign In to MyBikeTraffic.com' first (HTTP $getCode)."
                    )
                }

                val parsedForm = parseImportPageHtml(html, finalPageUrl)
                if (parsedForm.requiresLogin) {
                    return@withContext Pair(
                        UploadPhase.NEEDS_LOGIN,
                        "Not signed in — tap 'Sign In to MyBikeTraffic.com' to log in once."
                    )
                }

                // Step 2: Build multipart/form-data POST with .fit file bytes
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
                            "Session expired — tap 'Sign In to MyBikeTraffic.com' to reconnect."
                        )
                    }
                    else -> {
                        Pair(
                            UploadPhase.ERROR,
                            "Upload returned HTTP $postCode for ${fitFile.name}. You can also upload inside the built-in browser."
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
