package com.olive.app.support

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

// JVM repository and controller tests use real encryption with an isolated in-memory keystore.
class TestAndroidKeyStore : Provider("AndroidKeyStore", 1.0, "Test-only Android keystore") {
    init {
        put("KeyStore.AndroidKeyStore", Store::class.java.name)
        put("KeyGenerator.AES", AesGenerator::class.java.name)
        put("KeyPairGenerator.RSA", RsaGenerator::class.java.name)
    }

    companion object {
        private val keys = java.util.concurrent.ConcurrentHashMap<String, Key>()
        private val certificates = java.util.concurrent.ConcurrentHashMap<String, Certificate>()
        fun install(): AutoCloseable {
            check(Security.getProvider("AndroidKeyStore") == null)
            keys.clear()
            certificates.clear()
            Security.addProvider(TestAndroidKeyStore())
            return AutoCloseable {
                Security.removeProvider("AndroidKeyStore")
                keys.clear()
                certificates.clear()
            }
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineGetKey(alias: String, password: CharArray?) = keys[alias]
        override fun engineGetCertificate(alias: String) = certificates[alias]
        override fun engineGetCertificateChain(alias: String) = certificates[alias]?.let { arrayOf(it) }
        override fun engineGetCreationDate(alias: String) = Date(0)
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key
            chain?.firstOrNull()?.let { certificates[alias] = it }
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
            error("Encoded key import is outside this test fixture")
        }
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) { certificates[alias] = cert }
        override fun engineDeleteEntry(alias: String) { keys.remove(alias); certificates.remove(alias) }
        override fun engineAliases() = Collections.enumeration(keys.keys + certificates.keys)
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias) || certificates.containsKey(alias)
        override fun engineSize() = (keys.keys + certificates.keys).size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String) = certificates.containsKey(alias)
        override fun engineGetCertificateAlias(cert: Certificate) = certificates.entries.firstOrNull { it.value == cert }?.key
    }

    class AesGenerator : KeyGeneratorSpi() {
        private var alias = ""
        private var bits = 256
        override fun engineInit(random: SecureRandom?) {}
        override fun engineInit(keysize: Int, random: SecureRandom?) { bits = keysize }
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            val specification = params as KeyGenParameterSpec
            alias = specification.keystoreAlias
            if (specification.keySize > 0) bits = specification.keySize
        }
        override fun engineGenerateKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(bits) }.generateKey().also { keys[alias] = it }
    }

    class RsaGenerator : KeyPairGeneratorSpi() {
        private var alias = ""
        private var bits = 2048
        override fun initialize(keysize: Int, random: SecureRandom?) { bits = keysize }
        override fun initialize(params: AlgorithmParameterSpec, random: SecureRandom?) {
            val specification = params as KeyGenParameterSpec
            alias = specification.keystoreAlias
            if (specification.keySize > 0) bits = specification.keySize
        }
        override fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair().also { pair ->
                keys[alias] = pair.private
                certificates[alias] = object : Certificate("X.509") {
                    override fun getEncoded() = pair.public.encoded
                    override fun getPublicKey() = pair.public
                    override fun verify(key: PublicKey?) {}
                    override fun verify(key: PublicKey?, sigProvider: String?) {}
                    override fun toString() = "Test-only certificate"
                }
            }
    }
}
