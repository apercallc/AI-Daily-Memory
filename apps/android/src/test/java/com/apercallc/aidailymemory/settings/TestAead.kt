package com.apercallc.aidailymemory.settings

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig

/** A software AES-GCM AEAD standing in for the Keystore-backed one. */
fun newTestAead(): Aead {
    AeadConfig.register()
    return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

private val sharedAead: Aead by lazy { newTestAead() }

/** One AEAD for the whole test JVM, for tests that just need settings to work. */
fun testAead(): Aead = sharedAead
