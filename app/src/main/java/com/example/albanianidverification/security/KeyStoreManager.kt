package com.example.albanianidverification.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

object KeyStoreManager {
    private const val KEY_ALIAS = "VoterDeviceRSAKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * Generates an RSA KeyPair in the hardware KeyStore if it doesn't exist.
     * Returns the Base64-encoded Public Key to send to the backend.
     */
    fun getOrGeneratePublicKeyBase64(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .build()

            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
        }

        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Signs a string payload using the hardware-locked Private Key.
     */
    fun signData(payload: String): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(payload.toByteArray(Charsets.UTF_8))
        }

        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }
}