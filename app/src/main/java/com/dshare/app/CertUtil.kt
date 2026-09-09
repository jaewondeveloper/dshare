package com.dshare.app

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Generates a fresh self-signed TLS certificate (with the current LAN IP as a
 * Subject Alternative Name) on every app start, so getDisplayMedia() sees a
 * secure context in the browser after the user clicks through the one-time
 * self-signed warning.
 */
object CertUtil {

    private const val KEY_ALIAS = "dshare"
    val KEYSTORE_PASSWORD = "dshare-local".toCharArray()

    init {
        // Android ships its own stripped-down "BC" provider that shadows algorithms
        // (e.g. SHA256withRSA signers) the full BouncyCastle jar needs. Replace it.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    fun buildKeyStore(ipAddress: String): KeyStore {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(3650))
        val subject = X500Name("CN=DShare, O=DShare Local")
        val serial = BigInteger(64, java.security.SecureRandom())

        val sanNames = arrayOf(
            GeneralName(GeneralName.iPAddress, ipAddress),
            GeneralName(GeneralName.iPAddress, "127.0.0.1"),
            GeneralName(GeneralName.dNSName, "localhost")
        )

        val certBuilder = JcaX509v3CertificateBuilder(
            subject, serial, now, notAfter, subject, keyPair.public
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            .addExtension(Extension.subjectAlternativeName, false, GeneralNames(sanNames))

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)

        val certHolder = certBuilder.build(signer)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certHolder)

        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(KEY_ALIAS, keyPair.private, KEYSTORE_PASSWORD, arrayOf(cert))
        }
    }
}
