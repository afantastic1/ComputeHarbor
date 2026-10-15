/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudProviderCredentialStoreTest {
    @Test
    fun encryptsPersistsReplacesAndRemovesCredential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("pair-cloud-provider-auth", 0)
        preferences.edit().clear().commit()
        val store = CloudProviderCredentialStore(context)

        store.put("android:test", "secret-one")
        val ciphertext = preferences.all.values.filterIsInstance<String>().singleOrNull()
        assertFalse(ciphertext.isNullOrBlank())
        assertFalse(ciphertext.orEmpty().contains("secret-one"))
        assertEquals("secret-one", CloudProviderCredentialStore(context).get("android:test"))

        store.put("android:test", "secret-two")
        assertEquals("secret-two", store.get("android:test"))
        store.remove("android:test")
        assertNull(store.get("android:test"))
        preferences.edit().clear().commit()
    }
}
