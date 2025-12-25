// NIP-46 Nostr Connect implementation for nsec.app login
import { nip19, nip44, generateSecretKey, getPublicKey, finalizeEvent } from 'nostr-tools'
import { SimplePool } from 'nostr-tools/pool'

const NIP46_KIND = 24133
const DEFAULT_NIP46_RELAYS = ['wss://relay.nsec.app']

// Store for NIP-46 session
let nip46Session = null

// Get stored session
export function getNip46Session() {
  if (typeof window === 'undefined') return null
  
  try {
    const stored = localStorage.getItem('nurunuru_nip46_session')
    if (stored) {
      nip46Session = JSON.parse(stored)
      return nip46Session
    }
  } catch (e) {
    console.error('Failed to load NIP-46 session:', e)
  }
  return null
}

// Save session
function saveNip46Session(session) {
  nip46Session = session
  if (typeof window !== 'undefined') {
    localStorage.setItem('nurunuru_nip46_session', JSON.stringify(session))
  }
}

// Clear session
export function clearNip46Session() {
  nip46Session = null
  if (typeof window !== 'undefined') {
    localStorage.removeItem('nurunuru_nip46_session')
  }
}

// Check if NIP-46 is available
export function hasNip46() {
  return getNip46Session() !== null
}

// Generate local keypair for NIP-46 communication
function generateLocalKeypair() {
  const sk = generateSecretKey()
  const pk = getPublicKey(sk)
  return { 
    secretKey: bytesToHex(sk), 
    publicKey: pk 
  }
}

// Helper functions
function bytesToHex(bytes) {
  return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('')
}

function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2)
  for (let i = 0; i < hex.length; i += 2) {
    bytes[i / 2] = parseInt(hex.substr(i, 2), 16)
  }
  return bytes
}

// Parse bunker:// URL
export function parseBunkerUrl(url) {
  try {
    if (!url.startsWith('bunker://')) {
      throw new Error('Invalid bunker URL')
    }
    
    // bunker://<remote-signer-pubkey>?relay=<relay>&secret=<secret>
    const withoutScheme = url.slice(9)
    const [pubkeyPart, queryPart] = withoutScheme.split('?')
    
    let remotePubkey = pubkeyPart
    if (remotePubkey.startsWith('npub')) {
      const decoded = nip19.decode(remotePubkey)
      remotePubkey = decoded.data
    }
    
    const params = new URLSearchParams(queryPart || '')
    const relays = params.getAll('relay')
    const secret = params.get('secret')
    
    return {
      remotePubkey,
      relays: relays.length > 0 ? relays : DEFAULT_NIP46_RELAYS,
      secret
    }
  } catch (e) {
    console.error('Failed to parse bunker URL:', e)
    throw new Error('bunker URL の形式が正しくありません')
  }
}

// Create NIP-44 encrypted content
async function encryptNip44(content, senderSk, receiverPk) {
  const conversationKey = nip44.v2.utils.getConversationKey(
    hexToBytes(senderSk),
    receiverPk
  )
  return nip44.v2.encrypt(content, conversationKey)
}

// Decrypt NIP-44 content
async function decryptNip44(ciphertext, senderPk, receiverSk) {
  const conversationKey = nip44.v2.utils.getConversationKey(
    hexToBytes(receiverSk),
    senderPk
  )
  return nip44.v2.decrypt(ciphertext, conversationKey)
}

// Send NIP-46 request and wait for response
async function sendNip46Request(method, params, session) {
  const pool = new SimplePool()
  const requestId = Math.random().toString(36).substring(2)
  
  const request = {
    id: requestId,
    method,
    params: params || []
  }
  
  const content = await encryptNip44(
    JSON.stringify(request),
    session.localSecretKey,
    session.remotePubkey
  )
  
  const event = finalizeEvent({
    kind: NIP46_KIND,
    created_at: Math.floor(Date.now() / 1000),
    tags: [['p', session.remotePubkey]],
    content
  }, hexToBytes(session.localSecretKey))
  
  // Publish request
  await Promise.any(session.relays.map(relay => pool.publish([relay], event)))
  
  // Wait for response
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      sub.close()
      reject(new Error('NIP-46 request timeout'))
    }, 30000)
    
    const since = Math.floor(Date.now() / 1000) - 10
    const sub = pool.subscribeMany(
      session.relays,
      [{ 
        kinds: [NIP46_KIND], 
        '#p': [session.localPublicKey],
        since
      }],
      {
        onevent: async (event) => {
          try {
            const decrypted = await decryptNip44(
              event.content,
              event.pubkey,
              session.localSecretKey
            )
            const response = JSON.parse(decrypted)
            
            if (response.id === requestId) {
              clearTimeout(timeout)
              sub.close()
              
              if (response.error) {
                reject(new Error(response.error))
              } else {
                resolve(response.result)
              }
            }
          } catch (e) {
            console.error('Failed to process NIP-46 response:', e)
          }
        }
      }
    )
  })
}

// Connect to remote signer using bunker URL
export async function connectWithBunkerUrl(bunkerUrl) {
  const { remotePubkey, relays, secret } = parseBunkerUrl(bunkerUrl)
  const localKeypair = generateLocalKeypair()
  
  const session = {
    remotePubkey,
    relays,
    localSecretKey: localKeypair.secretKey,
    localPublicKey: localKeypair.publicKey,
    userPubkey: null
  }
  
  // Send connect request
  const pool = new SimplePool()
  const requestId = Math.random().toString(36).substring(2)
  
  const request = {
    id: requestId,
    method: 'connect',
    params: [localKeypair.publicKey, secret || '', 'sign_event']
  }
  
  const content = await encryptNip44(
    JSON.stringify(request),
    session.localSecretKey,
    remotePubkey
  )
  
  const event = finalizeEvent({
    kind: NIP46_KIND,
    created_at: Math.floor(Date.now() / 1000),
    tags: [['p', remotePubkey]],
    content
  }, hexToBytes(session.localSecretKey))
  
  // Publish connect request
  await Promise.any(relays.map(relay => pool.publish([relay], event)))
  
  // Wait for ACK
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      sub.close()
      reject(new Error('接続がタイムアウトしました。nsec.appで承認してください。'))
    }, 60000) // 60 seconds for user to approve
    
    const since = Math.floor(Date.now() / 1000) - 10
    const sub = pool.subscribeMany(
      relays,
      [{ 
        kinds: [NIP46_KIND], 
        '#p': [localKeypair.publicKey],
        since
      }],
      {
        onevent: async (ev) => {
          try {
            const decrypted = await decryptNip44(
              ev.content,
              ev.pubkey,
              session.localSecretKey
            )
            const response = JSON.parse(decrypted)
            
            if (response.id === requestId) {
              clearTimeout(timeout)
              sub.close()
              
              if (response.error) {
                reject(new Error(response.error))
              } else {
                // Connection successful, now get public key
                try {
                  const userPubkey = await sendNip46Request('get_public_key', [], session)
                  session.userPubkey = userPubkey
                  saveNip46Session(session)
                  resolve({ pubkey: userPubkey, session })
                } catch (e) {
                  reject(new Error('公開鍵の取得に失敗しました'))
                }
              }
            }
          } catch (e) {
            console.error('Failed to process connect response:', e)
          }
        }
      }
    )
  })
}

// Sign event using NIP-46
export async function signEventNip46(event) {
  const session = getNip46Session()
  if (!session) {
    throw new Error('NIP-46 session not found')
  }
  
  // Remove id and sig if present
  const eventToSign = {
    kind: event.kind,
    created_at: event.created_at || Math.floor(Date.now() / 1000),
    tags: event.tags || [],
    content: event.content || '',
    pubkey: session.userPubkey
  }
  
  const result = await sendNip46Request('sign_event', [JSON.stringify(eventToSign)], session)
  return JSON.parse(result)
}

// Get public key from NIP-46 session
export function getNip46PublicKey() {
  const session = getNip46Session()
  return session?.userPubkey || null
}
