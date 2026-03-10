/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Based on Divine mobile (https://github.com/verse-app/divine)
 * Copyright (c) Divine contributors
 */
package io.nurunuru.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Date
import kotlin.coroutines.resume

/**
 * Implements NIP-ProofMode for video recordings on Android.
 *
 * Verification levels:
 * - verified_mobile: Play Integrity attestation + PGP signature + frame hashes (highest)
 * - verified_web:    PGP signature + frame hashes (fallback if Play Integrity unavailable)
 * - basic_proof:     SHA-256 hash only (fallback if PGP signing fails)
 */
class ProofModeManager(private val context: Context) {

    companion object {
        private const val TAG = "ProofModeManager"
        private const val FRAME_SAMPLE_COUNT = 10
    }

    data class SensorReading(
        val timestamp: String,
        val accX: Float,
        val accY: Float,
        val accZ: Float,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float
    )

    data class ProofSession(
        val sessionId: String,
        val challengeNonce: String,
        val startTime: String
    )

    private val secureRandom = SecureRandom()

    fun startSession(): ProofSession {
        val sessionId = "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val nonceBytes = ByteArray(12)
        secureRandom.nextBytes(nonceBytes)
        val challengeNonce = nonceBytes.joinToString("") { "%02x".format(it) }.take(16)
        return ProofSession(sessionId, challengeNonce, Instant.now().toString())
    }

    /**
     * Finalize a recording session and generate Nostr ProofMode tags.
     *
     * @param session    Session started with [startSession]
     * @param videoFile  Completed video file to hash and analyze
     * @param endTime    ISO-8601 timestamp of recording end
     * @param sensorReadings Sensor readings collected during recording
     * @param totalDurationMs Total elapsed time (including any pauses)
     * @param recordingDurationMs Actual recorded duration
     * @return Nostr tags: verification, proofmode, device_attestation?, pgp_fingerprint?
     */
    suspend fun finalizeAndGetTags(
        session: ProofSession,
        videoFile: File,
        endTime: String,
        sensorReadings: List<SensorReading>,
        totalDurationMs: Long,
        recordingDurationMs: Long
    ): List<List<String>> = withContext(Dispatchers.IO) {
        try {
            val finalVideoHash = hashFile(videoFile)
            val frameHashes = extractFrameHashes(videoFile, FRAME_SAMPLE_COUNT)
            val sensorData = averageSensorData(sensorReadings)

            val segment = buildJsonObject {
                put("segmentId", "segment_1")
                put("startTime", session.startTime)
                put("endTime", endTime)
                put("duration", recordingDurationMs)
                put("frameHashes", JsonArray(frameHashes.map { JsonPrimitive(it) }))
                put("sensorData", sensorData)
            }

            val baseManifest = buildJsonObject {
                put("sessionId", session.sessionId)
                put("challengeNonce", session.challengeNonce)
                put("vineSessionStart", session.startTime)
                put("vineSessionEnd", endTime)
                put("totalDuration", totalDurationMs)
                put("recordingDuration", recordingDurationMs)
                put("segments", JsonArray(listOf(segment)))
                put("pauseProofs", JsonArray(emptyList()))
                put("interactions", JsonArray(listOf(
                    buildJsonObject {
                        put("timestamp", session.startTime)
                        put("interactionType", "start")
                        put("coordinates", buildJsonObject { put("x", 180); put("y", 640) })
                    },
                    buildJsonObject {
                        put("timestamp", endTime)
                        put("interactionType", "stop")
                        put("coordinates", buildJsonObject { put("x", 180); put("y", 640) })
                    }
                )))
                put("finalVideoHash", finalVideoHash)
            }

            val attestationToken = tryGetPlayIntegrityToken(session.challengeNonce)
            val (pgpSig, pgpPubKey, pgpFingerprint) = tryPgpSign(baseManifest.toString())

            val finalManifest = buildJsonObject {
                baseManifest.forEach { key, value -> put(key, value) }

                if (attestationToken != null) {
                    put("deviceAttestation", buildJsonObject {
                        put("token", attestationToken)
                        put("platform", "Android")
                        put("deviceId", getAnonymizedDeviceId())
                        put("isHardwareBacked", true)
                        put("createdAt", endTime)
                        put("challenge", session.challengeNonce)
                        put("metadata", buildJsonObject {
                            put("attestationType", "play_integrity")
                            put("deviceInfo", buildJsonObject {
                                put("platform", "Android")
                                put("model", android.os.Build.MODEL)
                                put("version", android.os.Build.VERSION.RELEASE)
                                put("manufacturer", android.os.Build.MANUFACTURER)
                            })
                        })
                    })
                }

                if (pgpSig != null) {
                    put("pgpSignature", buildJsonObject {
                        put("signature", pgpSig)
                        put("publicKey", pgpPubKey ?: "")
                        put("publicKeyFingerprint", pgpFingerprint ?: "")
                    })
                }
            }

            val verificationLevel = when {
                attestationToken != null && pgpSig != null -> "verified_mobile"
                pgpSig != null -> "verified_web"
                else -> "basic_proof"
            }

            Log.d(TAG, "ProofMode: $verificationLevel (frames=${frameHashes.size}, attestation=${attestationToken != null})")

            val tags = mutableListOf(
                listOf("x", finalVideoHash),
                listOf("verification", verificationLevel),
                listOf("proofmode", finalManifest.toString())
            )
            pgpFingerprint?.let { tags.add(listOf("pgp_fingerprint", it)) }
            attestationToken?.let { tags.add(listOf("device_attestation", it)) }

            tags
        } catch (e: Exception) {
            Log.e(TAG, "ProofMode finalization failed", e)
            // Minimal fallback: at least provide the file hash
            try {
                listOf(
                    listOf("x", hashFile(videoFile)),
                    listOf("verification", "unverified")
                )
            } catch (_: Exception) {
                listOf(listOf("verification", "unverified"))
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var n: Int
            while (stream.read(buffer).also { n = it } != -1) {
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractFrameHashes(videoFile: File, count: Int): List<String> {
        val retriever = MediaMetadataRetriever()
        val hashes = mutableListOf<String>()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 6300L
            val interval = durationMs / (count + 1)
            val digest = MessageDigest.getInstance("SHA-256")

            repeat(count) { i ->
                val timeUs = interval * (i + 1) * 1000L
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null) {
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                    digest.reset()
                    digest.update(baos.toByteArray())
                    hashes.add(digest.digest().joinToString("") { "%02x".format(it) })
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame extraction failed: ${e.message}")
        } finally {
            retriever.release()
        }
        return hashes
    }

    private fun averageSensorData(readings: List<SensorReading>): JsonObject {
        if (readings.isEmpty()) return buildJsonObject {}
        val n = readings.size.toDouble()
        return buildJsonObject {
            put("accelerometer", buildJsonObject {
                put("x", readings.sumOf { it.accX.toDouble() } / n)
                put("y", readings.sumOf { it.accY.toDouble() } / n)
                put("z", readings.sumOf { it.accZ.toDouble() } / n)
            })
            put("gyroscope", buildJsonObject {
                put("x", readings.sumOf { it.gyroX.toDouble() } / n)
                put("y", readings.sumOf { it.gyroY.toDouble() } / n)
                put("z", readings.sumOf { it.gyroZ.toDouble() } / n)
            })
        }
    }

    private suspend fun tryGetPlayIntegrityToken(nonce: String): String? = try {
        withContext(Dispatchers.IO) {
            val nonce64 = Base64.encodeToString(
                nonce.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            val manager = com.google.android.play.core.integrity.IntegrityManagerFactory.create(context)
            suspendCancellableCoroutine { cont ->
                manager.requestIntegrityToken(
                    com.google.android.play.core.integrity.IntegrityTokenRequest.builder()
                        .setNonce(nonce64)
                        .build()
                ).addOnSuccessListener { response ->
                    cont.resume(response.token())
                }.addOnFailureListener { e ->
                    Log.w(TAG, "Play Integrity unavailable: ${e.message}")
                    cont.resume(null)
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Play Integrity not available: ${e.message}")
        null
    }

    /**
     * Generate an ephemeral Ed25519 PGP key pair and sign [content].
     * Uses Bouncy Castle lightweight (Bc*) operators — no JCE provider needed.
     *
     * @return Triple(armored signature, armored public key, fingerprint hex) or nulls on failure
     */
    private fun tryPgpSign(content: String): Triple<String?, String?, String?> = try {
        // 1. Generate Ed25519 key pair
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(secureRandom))
        val kp = gen.generateKeyPair()

        // 2. Wrap in PGP
        val pgpKp = BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA_LEGACY, kp, Date())

        // 3. Build key ring (unencrypted private key — ephemeral, single use)
        val digestCalcProvider = BcPGPDigestCalculatorProvider()
        val sha1Calc = digestCalcProvider.get(HashAlgorithmTags.SHA1)
        val keyRingGen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKp,
            "NuruNuru ProofMode",
            sha1Calc,
            null,
            null,
            BcPGPContentSignerBuilder(pgpKp.publicKey.algorithm, HashAlgorithmTags.SHA256),
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.NULL, sha1Calc).build(null)
        )
        val secretKeyRing = keyRingGen.generateSecretKeyRing()
        val publicKeyRing = keyRingGen.generatePublicKeyRing()

        // 4. Export public key as ASCII armor
        val pubBaos = ByteArrayOutputStream()
        ArmoredOutputStream(pubBaos).use { publicKeyRing.encode(it) }
        val armoredPubKey = pubBaos.toString(Charsets.UTF_8.name())

        // 5. Fingerprint
        val fingerprint = publicKeyRing.publicKey.fingerprint
            .joinToString("") { "%02X".format(it) }

        // 6. Sign content (detached, binary document)
        val secretKey = secretKeyRing.secretKey
        val privateKey = secretKey.extractPrivateKey(
            BcPBESecretKeyDecryptorBuilder(digestCalcProvider).build(null)
        )
        val sigGen = PGPSignatureGenerator(
            BcPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256)
        )
        sigGen.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        sigGen.update(content.toByteArray(Charsets.UTF_8))

        val sigBaos = ByteArrayOutputStream()
        ArmoredOutputStream(sigBaos).use { sigGen.generate().encode(it) }
        val armoredSig = sigBaos.toString(Charsets.UTF_8.name())

        Triple(armoredSig, armoredPubKey, fingerprint)
    } catch (e: Exception) {
        Log.e(TAG, "PGP signing failed", e)
        Triple(null, null, null)
    }

    /** SHA-256 of the Android ID, truncated to 16 hex chars for privacy. */
    private fun getAnonymizedDeviceId(): String {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(androidId.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }
}
