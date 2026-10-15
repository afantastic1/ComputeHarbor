/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import android.content.Context
import android.util.Base64
import java.security.Key
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Stores provider credentials encrypted with a dedicated non-exportable Android Keystore key. */
class CloudProviderCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun put(authRef: String, credential: String) {
        require(authRef.isNotBlank()) { "provider credential reference is required" }
        if (credential.isEmpty()) {
            remove(authRef)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(credential.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(preferenceKey(authRef), Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "could not persist provider credential"
        }
    }

    @Synchronized
    fun get(authRef: String): String? {
        if (authRef.isBlank()) return null
        val key = preferenceKey(authRef)
        val encrypted = preferences.getString(key, null) ?: return null
        return runCatching { decrypt(Base64.decode(encrypted, Base64.NO_WRAP)) }
            .onFailure { preferences.edit().remove(key).commit() }
            .getOrNull()
    }

    @Synchronized
    fun remove(authRef: String) {
        if (authRef.isNotBlank()) preferences.edit().remove(preferenceKey(authRef)).commit()
    }

    @Synchronized
    fun clearAll() {
        preferences.edit().clear().commit()
    }

    private fun decrypt(value: ByteArray): String {
        require(value.size > GCM_IV_BYTES) { "invalid encrypted provider credential" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, value.copyOfRange(0, GCM_IV_BYTES)),
        )
        return cipher.doFinal(value.copyOfRange(GCM_IV_BYTES, value.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it }
        return KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun preferenceKey(authRef: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(authRef.toByteArray(Charsets.UTF_8))
        return CREDENTIAL_PREFIX + Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val PREFERENCES = "pair-cloud-provider-auth"
        const val CREDENTIAL_PREFIX = "credential-"
        const val KEY_ALIAS = "pair-cloud-provider-auth"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
