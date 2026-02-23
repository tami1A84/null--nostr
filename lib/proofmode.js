import * as openpgp from 'openpgp'

/**
 * Calculate SHA-256 hash of a Blob
 * @param {Blob} blob
 * @returns {Promise<string>} Hex hash
 */
export async function calculateSHA256(blob) {
  if (typeof window === 'undefined') return ''
  const arrayBuffer = await blob.arrayBuffer()
  const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}

/**
 * Generate ProofMode tags for a video blob (verified_web level)
 * @param {Blob} videoBlob
 * @returns {Promise<string[][]>} Nostr tags
 */
export async function generateProofModeTags(videoBlob) {
  if (typeof window === 'undefined') return []

  try {
    const videoHash = await calculateSHA256(videoBlob)

    // 1. Generate ephemeral Ed25519 PGP key pair
    const { privateKey, publicKey } = await openpgp.generateKey({
      type: 'ecc',
      curve: 'ed25519',
      userIDs: [{ name: 'Null-nostr ProofMode', email: 'proof@null-nostr.internal' }],
      format: 'armored'
    })

    // 2. Build manifest
    const manifestObj = {
      "File Hash SHA256": videoHash,
      "Creation Time": new Date().toISOString(),
      "Capture Environment": "Web Browser",
      "User Agent": navigator.userAgent,
      "Verification Level": "verified_web"
    }
    const manifestString = JSON.stringify(manifestObj, null, 2)

    // 3. Sign the manifest (detached signature)
    const message = await openpgp.createMessage({ text: manifestString })
    const signature = await openpgp.sign({
      message,
      signingKeys: privateKey,
      detached: true
    })

    // 4. Base64 encode manifest for the tag
    const base64Manifest = btoa(unescape(encodeURIComponent(manifestString)))

    // Build diVine compatible proofmode tag
    const proofmodeObj = {
      manifest: manifestObj,
      signature: signature,
      pubkey: publicKey
    }

    return [
      ["x", videoHash],
      ["verification", "verified_web"],
      ["proofmode", JSON.stringify(proofmodeObj)]
    ]
  } catch (error) {
    console.error('ProofMode generation failed:', error)

    // Fallback to basic hash if anything fails
    const hash = await calculateSHA256(videoBlob)
    return [
      ["x", hash],
      ["verification", "unverified"]
    ]
  }
}
