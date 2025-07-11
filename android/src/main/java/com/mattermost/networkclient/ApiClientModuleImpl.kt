package com.mattermost.networkclient

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.network.ForwardingCookieHandler
import com.facebook.react.modules.network.ReactCookieJarContainer
import com.mattermost.networkclient.helpers.KeyStoreHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.JavaNetCookieJar
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class ApiClientModuleImpl(appContext: Context) {
    companion object {
        const val NAME = "ApiClient"

        lateinit var context: Context
        private val clients = mutableMapOf<HttpUrl, NetworkClient>()
        private val calls = mutableMapOf<String, Call>()
        private lateinit var sharedPreferences: SharedPreferences
        private const val SHARED_PREFERENCES_NAME = "APIClientPreferences"
        private const val DATASTORE_NAME = "APIClientDataStore"
        internal val cookieJar = ReactCookieJarContainer()
        private val aliasTokenCache = mutableMapOf<String, String?>()

        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

        internal fun getClientForRequest(request: Request): NetworkClient? {
            var urlParts = request.url.toString().split("/")
            while (urlParts.isNotEmpty()) {
                val url = urlParts.joinToString(separator = "/") { it }.toHttpUrlOrNull()
                if (url !== null && clients.containsKey(url)) {
                    return clients[url]!!
                }

                urlParts = urlParts.dropLast(1)
            }

            return null
        }

        internal fun storeValue(value: String, alias: String) {
            val encryptedValue = KeyStoreHelper.encryptData(value)
            runBlocking {
                context.dataStore.edit { preferences ->
                    preferences[stringPreferencesKey(alias)] = encryptedValue
                }
            }
        }

        internal fun retrieveValue(alias: String): String? {
            val cacheData = this.aliasTokenCache[alias]
            if (cacheData != null) {
                return cacheData
            }

            return runBlocking {
                val encryptedData = context.dataStore.data.first()[stringPreferencesKey(alias)]
                if (encryptedData != null) {
                    val data = KeyStoreHelper.decryptData(encryptedData)
                    aliasTokenCache[alias] = data
                    return@runBlocking data
                }
                null
            }
        }

        internal fun deleteValue(alias: String) {
            runBlocking {
                context.dataStore.edit { preferences ->
                    preferences.remove(stringPreferencesKey(alias))
                }
                aliasTokenCache.remove(alias)
            }
        }

        internal fun sendJSEvent(eventName: String, data: WritableMap?) {
            val reactApplicationContext = context as? ReactApplicationContext
            if (reactApplicationContext?.hasActiveReactInstance() == true) {
                reactApplicationContext.emitDeviceEvent(eventName, data)

            }
        }

        private fun setCtx(reactContext: Context) {
            context = reactContext
        }

        private fun migrateSharedPreferences(reactContext: Context) {
            val sharedPreferences = reactContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

            if (sharedPreferences.all.isNotEmpty()) {
                runBlocking {
                    reactContext.dataStore.edit { preferences ->
                        sharedPreferences.all.forEach { entry ->
                            val key = stringPreferencesKey(entry.key)
                            when (val value = entry.value) {
                                is String -> preferences[key] = value
                            }
                        }
                    }

                    sharedPreferences.edit().clear().apply()
                }
            }
        }

        private fun setCookieJar(reactContext: Context) {
            val reactApplicationContext = reactContext as? ReactApplicationContext
            if (reactApplicationContext != null) {
                val cookieHandler = ForwardingCookieHandler(reactApplicationContext)
                cookieJar.setCookieJar(JavaNetCookieJar(cookieHandler))
            }
        }
    }

    init {
        setCtx(appContext)
        migrateSharedPreferences(appContext)
        setCookieJar(appContext)
    }

    fun createClientFor(baseUrl: String, options: ReadableMap, promise: Promise) {
        val url: HttpUrl
        try {
            url = baseUrl.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            return promise.reject(error)
        }

        try {
            clients[url] = NetworkClient(context, url, options, cookieJar)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject(error)
        }
    }

    fun getClientHeadersFor(baseUrl: String, promise: Promise) {
        val url: HttpUrl
        try {
            url = baseUrl.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            return promise.reject(error)
        }

        try {
            promise.resolve(clients[url]!!.clientHeaders)
        } catch (error: Exception) {
            promise.reject(error)
        }
    }

    fun addClientHeadersFor(baseUrl: String, headers: ReadableMap, promise: Promise) {
        val url: HttpUrl
        try {
            url = baseUrl.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            return promise.reject(error)
        }

        try {
            clients[url]!!.addClientHeaders(headers)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject(error)
        }
    }

    fun importClientP12For(baseUrl: String, path: String, password: String, promise: Promise) {
        val url: HttpUrl
        try {
            url = baseUrl.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            return promise.reject(error)
        }

        try {
            clients[url]!!.importClientP12AndRebuildClient(path, password)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject(error)
        }
    }

    fun invalidateClientFor(baseUrl: String, promise: Promise) {
        val url: HttpUrl
        try {
            url = baseUrl.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            return promise.reject(error)
        }

        try {
            clients[url]!!.invalidate()
            clients.remove(url)
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject(error)
        }
    }

    fun head(baseUrl: String, endpoint: String, options: ReadableMap?, promise: Promise) {
        request("HEAD", baseUrl, endpoint, options, promise)
    }

    fun get(baseUrl: String, endpoint: String, options: ReadableMap?, promise: Promise) {
        request("GET", baseUrl, endpoint, options, promise)
    }

    fun post(baseUrl: String, endpoint: String, options: ReadableMap?, promise: Promise) {
        request("POST", baseUrl, endpoint, options, promise)
    }

    fun put(baseUrl: String, endpoint: String, options: ReadableMap?, promise: Promise) {
        request("PUT", baseUrl, endpoint, options, promise)
    }

    fun patch(baseUrl: String, endpoint: String, options: ReadableMap?, promise: Promise) {
        request("PATCH", baseUrl, endpoint, options, promise)
    }

    fun delete(baseUrl: String, endpoint: String, options: ReadableMap?, promise: Promise) {
        request("DELETE", baseUrl, endpoint, options, promise)
    }

    fun download(baseUrl: String, endpoint: String, filePath: String, taskId: String, options: ReadableMap?, promise: Promise) {
        val url: HttpUrl
        try {
            url = baseUrl.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            return promise.reject(error)
        }

        val f = File(filePath)
        val parent = f.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return promise.reject(Error("Couldn't create dir: " + parent.path))
        }

        val client = clients[url]!!
        val downloadCall = client.buildDownloadCall(endpoint, taskId, options)
        calls[taskId] = downloadCall

        try {
            downloadCall.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    calls.remove(taskId)
                    promise.reject(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    var inputStream: InputStream? = null
                    var outputStream: FileOutputStream? = null
                    try {
                        val responseBody = response.body
                        if (responseBody != null) {
                            inputStream = responseBody.byteStream()
                            outputStream = FileOutputStream(f)

                            val buffer = ByteArray(2 * 1024)
                            var len: Int
                            var readLen = 0
                            while (inputStream.read(buffer).also { len = it } != -1) {
                                outputStream.write(buffer, 0, len)
                                readLen += len
                            }
                            promise.resolve(response.toDownloadMap(filePath))
                        } else {
                            promise.reject(Error("Response body empty"))
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        promise.reject(e)
                    } finally {
                        try {
                            calls.remove(taskId)
                            inputStream?.close()
                            outputStream?.close()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    client.cleanUpAfter(response)
                }
            })
        } catch (error: Exception) {
            calls.remove(taskId)
            promise.reject(error)
        }
    }

    fun upload(baseUrl: String, endpoint: String, filePath: String, taskId: String, options: ReadableMap?, promise: Promise) {
        val url: HttpUrl
        try {
            url = baseUrl.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            return promise.reject(error)
        }

        val client = clients[url]!!
        val uploadCall = client.buildUploadCall(endpoint, filePath, taskId, options)
        calls[taskId] = uploadCall

        try {
            uploadCall.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    calls.remove(taskId)
                    promise.reject(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    promise.resolve(response.toWritableMap(null))
                    client.cleanUpAfter(response)
                    calls.remove(taskId)
                }
            })
        } catch (error: Exception) {
            promise.reject(error)
            calls.remove(taskId)
        }
    }

    fun cancelRequest(taskId: String, promise: Promise) {
        try {
            val call = calls[taskId]
            if (call != null) {
                call.cancel()
                calls.remove(taskId)
            }
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject(error)
        }
    }

    private fun request(method: String, baseUrl: String, endpoint: String, options: ReadableMap?, promise: Promise) {
        try {
            val url = baseUrl.toHttpUrl()
            val client = clients[url]
            client?.request(method, endpoint, options, promise)
        } catch (error: Exception) {
            return promise.reject(error)
        }
    }

    // Methods to use with native implementations
    fun hasClientFor(url: HttpUrl): Boolean {
        return clients.containsKey(url)
    }

    private fun requestSync(method: String, baseUrl: String, endpoint: String, options: ReadableMap?): Response? {
        val url = baseUrl.toHttpUrl()
        val client = clients[url]
        return client?.requestSync(method, endpoint, options)
    }

    fun getSync(baseUrl: String, endpoint: String, options: ReadableMap?): Response? {
        return requestSync("GET", baseUrl, endpoint, options)
    }

    fun postSync(baseUrl: String, endpoint: String, options: ReadableMap?): Response? {
        return requestSync("POST", baseUrl, endpoint, options)
    }
}
