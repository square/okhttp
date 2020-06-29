package okhttp.android.test.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.internal.platform.Platform
import javax.net.ssl.KeyManagerFactory
import okhttp3.Request
import okhttp3.tls.*
import javax.net.ssl.X509ExtendedKeyManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.assertj.core.api.Assertions
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal
import org.conscrypt.Conscrypt
import java.security.cert.Certificate

// https://source.android.com/security/keystore/attestation#attestation-keys-and-certificates
// https://developer.android.com/training/articles/security-key-attestation
// https://medium.com/bugbountywriteup/android-key-attestation-581da703ac16
// https://www.iedigital.com/blog/create-rsa-key-pair-on-android/
@RunWith(AndroidJUnit4::class)
class AttestTest {
  @Test fun get() {
    java.security.Security.insertProviderAt(Conscrypt.newProvider(), 1)

    val keyStore = KeyStore.getInstance("AndroidKeyStore")
        .apply {
          load(null)
        }

//    keyStore.deleteEntry("test14")

    val key = getOrCreateKeyPair(keyStore, "test14")
    val certificate = keyStore.getCertificate("test14") as X509Certificate

    println(certificate)

    println(certificate.certificatePem())

    printcert(certificate)
  }

  private fun getOrCreateKeyPair(
    keyStore: KeyStore,
    keyName: String
  ): KeyPair {
    keyStore.getKey(keyName, null)
        ?.let {
          println("Found $it")
          val cert = keyStore.getCertificate(keyName)
          return KeyPair(cert.publicKey, it as PrivateKey)
        }

    val paramsBuilder = KeyGenParameterSpec.Builder(
        keyName,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    )
        .apply {
          setCertificateSerialNumber(1001.toBigInteger())
          setCertificateSubject(X500Principal("CN=Yuri Schimke"))
          setDigests(KeyProperties.DIGEST_SHA256)
          setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
//          setUserAuthenticationRequired(true) // lock screen
//          setUnlockedDeviceRequired(true)
//          setUserConfirmationRequired(true)
//          setUserAuthenticationValidityDurationSeconds(30)
//          setUserAuthenticationParameters(15, AUTH_BIOMETRIC_STRONG)
          setIsStrongBoxBacked(true)
//          setInvalidatedByBiometricEnrollment(true)
            setAttestationChallenge(ByteArray(32) { 'Y'.toByte() })
        }

    val keyGenParams = paramsBuilder.build()
    val keyGenerator = KeyPairGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_RSA,
        "AndroidKeyStore"
    )
    keyGenerator.initialize(keyGenParams)
    val generateKey = keyGenerator.generateKeyPair()

    println("Generated $generateKey")
    println("" + generateKey.private)
    println("" + generateKey.public)

    return generateKey
  }
}