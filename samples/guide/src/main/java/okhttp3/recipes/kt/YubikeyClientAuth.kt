/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("Since15")

package okhttp3.recipes.kt

import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.KeyStoreBuilderParameters
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.callback.Callback
import javax.security.auth.callback.CallbackHandler
import javax.security.auth.callback.PasswordCallback
import javax.security.auth.callback.UnsupportedCallbackException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.Platform

/**
 * Example of using a hardware key to perform client auth.
 * Prefer recent JDK builds, and results are temperamental to slight environment changes.
 * Different instructions and configuration may be required for other hardware devices.
 *
 * Using a yubikey device as a SSL key store.
 * https://lauri.võsandi.com/2017/03/yubikey-for-ssh-auth.html
 *
 * Using PKCS11 support in the JDK.
 * https://tersesystems.com/blog/2018/09/08/keymanagers-and-keystores/
 *
 * Install OpenSC separately. On a mac `brew cast install opensc`.
 */
@SuppressSignatureCheck
class YubikeyClientAuth {
  fun run() {
    // The typical PKCS11 slot, may vary with different hardware.
    val slot = 0

    val config = "--name=OpenSC\nlibrary=/Library/OpenSC/lib/opensc-pkcs11.so\nslot=$slot\n"

    // May fail with ProviderException with root cause like
    // sun.security.pkcs11.wrapper.PKCS11Exception: CKR_SLOT_ID_INVALID
    val pkcs11 = Security.getProvider("SunPKCS11").configure(config)
    Security.addProvider(pkcs11)

    val callbackHandler = ConsoleCallbackHandler

    val builderList: List<KeyStore.Builder> =
      listOf(
        KeyStore.Builder.newInstance("PKCS11", null, KeyStore.CallbackHandlerProtection(callbackHandler)),
        // Example if you want to combine multiple keystore types
        // KeyStore.Builder.newInstance("PKCS12", null, File("keystore.p12"), PasswordProtection("rosebud".toCharArray()))
      )

    val keyManagerFactory = KeyManagerFactory.getInstance("NewSunX509")
    keyManagerFactory.init(KeyStoreBuilderParameters(builderList))
    val keyManager = keyManagerFactory.keyManagers[0] as X509ExtendedKeyManager

    val trustManager = Platform.get().platformTrustManager()

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())

    val client =
      OkHttpClient
        .Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .build()

    // An example test URL that returns client certificate details.
    val request =
      Request
        .Builder()
        .url("https://prod.idrix.eu/secure/")
        .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Unexpected code $response")

      println(response.body.string())
    }
  }
}

object ConsoleCallbackHandler : CallbackHandler {
  override fun handle(callbacks: Array<Callback>) {
    for (callback in callbacks) {
      if (callback is PasswordCallback) {
        val console = System.console()

        if (console != null) {
          callback.password = console.readPassword(callback.prompt)
        } else {
          System.err.println(callback.prompt)
          callback.password =
            System.`in`
              .bufferedReader()
              .readLine()
              .toCharArray()
        }
      } else {
        throw UnsupportedCallbackException(callback)
      }
    }
  }
}

fun main() {
  YubikeyClientAuth().run()
}
