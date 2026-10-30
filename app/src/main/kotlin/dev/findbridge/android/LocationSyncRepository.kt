package dev.findbridge.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/** Optional owner-configured data source. Only a pinned HTTPS snapshot GET is supported. */
internal class LocationSyncRepository private constructor(context: Context) {
    private val application = context.applicationContext as android.app.Application
    private val resolver = context.applicationContext.contentResolver
    private val store = SecureSessionStore(context, "location_source")
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<AppleRepository.Listener>()
    private var configuration = store.load()
    private var generation = 0
    var people: List<SharedLocation> = emptyList()
        private set

    var items: List<SharedLocation> = emptyList()
        private set

    var pencils: List<SharedLocation> = emptyList()
        private set

    var loading = false
        private set

    private var errorRes = 0
    private var remoteError = ""
    val error
        get() = if (errorRes == 0) remoteError else AppLanguage.text(application, errorRes)

    var sourceName = ""
        private set

    val configured
        get() = configuration != null

    fun addListener(listener: AppleRepository.Listener) {
        listeners += listener
    }

    fun removeListener(listener: AppleRepository.Listener) {
        listeners -= listener
    }

    private fun changed() {
        listeners.toList().forEach { it.onChanged() }
    }

    fun importFile(uri: Uri) {
        val requestGeneration = ++generation
        loading = true
        changed()
        executor.execute {
            runCatching {
                val text =
                    resolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readTextLimited()
                    } ?: error("Cannot read file")
                val json = JSONObject(text)
                if (json.optString("type") == "findbridge.pairing") {
                    validatePairing(json)
                    store.save(json)
                    main.post {
                        if (generation != requestGeneration) return@post
                        configuration = json
                        loading = false
                        refresh()
                    }
                } else {
                    require(
                        json.optJSONArray("people") != null ||
                            json.optJSONArray("items") != null ||
                            json.optJSONArray("devices") != null
                    )
                    val saved =
                        JSONObject().put("file", uri.toString()).put("type", "findbridge.file")
                    store.save(saved)
                    main.post {
                        if (generation != requestGeneration) return@post
                        configuration = saved
                        loading = false
                        applySnapshot(json)
                    }
                }
            }
                .onFailure {
                    main.post {
                        if (generation != requestGeneration) return@post
                        loading = false
                        errorRes = R.string.source_import_failed
                        changed()
                    }
                }
        }
    }

    private fun java.io.Reader.readTextLimited(): String {
        val text = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            text.append(buffer, 0, count)
            require(text.length <= 4_000_000)
        }
        return text.toString()
    }

    private fun validatePairing(json: JSONObject) {
        val url = URI(json.getString("server"))
        require(
            url.scheme == "https" &&
                url.host != null &&
                url.userInfo == null &&
                url.query == null &&
                url.fragment == null
        )
        require(url.path.isNullOrEmpty() || url.path == "/")
        require(json.getString("token").length >= 32)
        require(json.getString("fingerprint").replace(":", "").matches(Regex("[a-fA-F0-9]{64}")))
    }

    fun refresh() {
        val saved = configuration ?: return
        if (loading) return
        val requestGeneration = generation
        loading = true
        changed()
        executor.execute {
            runCatching {
                val json =
                    if (saved.has("file")) {
                        val uri = Uri.parse(saved.getString("file"))
                        JSONObject(
                            resolver.openInputStream(uri)!!.bufferedReader().use {
                                it.readTextLimited()
                            }
                        )
                    } else fetchSnapshot(saved)
                require(
                    json.optJSONArray("people") != null ||
                        json.optJSONArray("items") != null ||
                        json.optJSONArray("devices") != null
                )
                main.post {
                    if (generation != requestGeneration) return@post
                    loading = false
                    applySnapshot(json)
                }
            }
                .onFailure {
                    main.post {
                        if (generation != requestGeneration) return@post
                        loading = false
                        errorRes = R.string.source_read_failed
                        changed()
                    }
                }
        }
    }

    @android.annotation.SuppressLint(
        "CustomX509TrustManager"
    ) // Platform trust validation plus an exact leaf certificate pin.
    private fun fetchSnapshot(saved: JSONObject): JSONObject {
        validatePairing(saved)
        val address = URI(saved.getString("server").trimEnd('/') + "/v1/snapshot")
        val expected = saved.getString("fingerprint").replace(":", "").lowercase()
        fun matches(cert: X509Certificate): Boolean {
            cert.checkValidity()
            val actual =
                MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") {
                    "%02x".format(it)
                }
            return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
        }
        val platformTrust =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply {
                    init(null as KeyStore?)
                }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    throw java.security.cert.CertificateException(
                        "Client authentication is not used"
                    )

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    platformTrust.checkServerTrusted(chain, authType)
                    if (chain.isEmpty() || !matches(chain[0]))
                        throw java.security.cert.CertificateException(
                            "Pinned server does not match"
                        )
                }
            }
        val tls =
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trust), SecureRandom())
            }
        val connection = address.toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory = tls.socketFactory
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("Authorization", "Bearer " + saved.getString("token"))
        connection.setRequestProperty("Accept", "application/json")
        try {
            require(connection.responseCode == 200)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readTextLimited() })
        } finally {
            connection.disconnect()
        }
    }

    private fun applySnapshot(json: JSONObject) {
        require(
            json.optJSONArray("people") != null ||
                json.optJSONArray("items") != null ||
                json.optJSONArray("devices") != null
        )
        sourceName = json.optString("source", AppLanguage.text(application, R.string.source_data))
        fun entries(key: String, category: LocationCategory): List<SharedLocation> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.let { value ->
                    SharedLocation.parse(value, category, sourceName)
                }
            }
        }
        people = entries("people", LocationCategory.PEOPLE)
        val allItems = entries("items", LocationCategory.ITEMS)
        pencils =
            (allItems + entries("devices", LocationCategory.ITEMS))
                .filter { it.pencil }
                .distinctBy { it.id }
        items = allItems.filterNot { it.pencil }
        errorRes = 0
        remoteError = json.optString("message")
        changed()
    }

    fun disconnect() {
        generation++
        loading = false
        configuration = null
        people = emptyList()
        items = emptyList()
        pencils = emptyList()
        errorRes = 0
        remoteError = ""
        sourceName = ""
        executor.execute { store.clear() }
        changed()
    }

    companion object {
        @Volatile private var instance: LocationSyncRepository? = null

        fun get(context: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: LocationSyncRepository(context).also { instance = it }
                }
    }
}
