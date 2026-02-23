'use client'

import { useState, useEffect } from 'react'
import { nip19 } from 'nostr-tools'
import { savePubkey, setStoredPrivateKey, publishRelayListMetadata } from '@/lib/nostr'
import { autoDetectRelays, formatDistance, REGION_COORDINATES, selectRelaysByRegion } from '@/lib/geohash'

/**
 * SignUpModal Component
 *
 * Handles new user registration including:
 * 1. Nostr key generation & Passkey registration via Nosskey
 * 2. Automatic relay setup based on geolocation
 */
export default function SignUpModal({ onClose, onSuccess, nosskeyManager }) {
  const [step, setStep] = useState('welcome') // welcome, creating, relay, success
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [createdPubkey, setCreatedPubkey] = useState(null)
  const [recommendedRelays, setRecommendedRelays] = useState([])
  const [locationInfo, setLocationInfo] = useState(null)
  const [selectionMode, setSelectionMode] = useState('auto') // auto, manual

  // Handle account creation
  const handleCreateAccount = async () => {
    setLoading(true)
    setError('')
    try {
      if (!nosskeyManager) throw new Error('Passkey manager not initialized')

      // 1. Create passkey
      const credentialId = await nosskeyManager.createPasskey({
        rp: { name: 'ぬるぬる' },
        user: { name: 'user', displayName: 'Nostr User' }
      })

      // 2. Create Nostr key
      const result = await nosskeyManager.createNostrKey(credentialId, {
        username: 'ぬるぬる'
      })

      if (result.pubkey) {
        nosskeyManager.setCurrentKeyInfo(result)
        setCreatedPubkey(result.pubkey)

        // Try to export private key for seamless relay setup
        try {
          const privateKeyHex = await nosskeyManager.exportNostrKey(result)
          if (privateKeyHex) {
            setStoredPrivateKey(result.pubkey, privateKeyHex)
          }
        } catch (e) {
          console.log('Temporary key export failed, will prompt for signature later:', e)
        }

        setStep('relay')
        startRelayDetection()
      } else {
        throw new Error('公開鍵の生成に失敗しました')
      }
    } catch (e) {
      console.error('Signup error:', e)
      if (e.name === 'NotAllowedError') {
        setError('登録がキャンセルされました')
      } else {
        setError(e.message || 'エラーが発生しました')
      }
    } finally {
      setLoading(false)
    }
  }

  // Handle relay detection
  const startRelayDetection = async () => {
    setLoading(true)
    try {
      const result = await autoDetectRelays()
      if (result.nearestRelays && result.nearestRelays.length > 0) {
        setRecommendedRelays(result.nearestRelays)
        setLocationInfo(result.region || { name: '検出された地域' })
        setSelectionMode('auto')
      } else {
        // Fallback to manual if auto fails
        setSelectionMode('manual')
      }
    } catch (e) {
      console.error('Relay detection failed:', e)
      setSelectionMode('manual')
    } finally {
      setLoading(false)
    }
  }

  // Handle manual region selection
  const handleRegionSelect = (regionId) => {
    if (!regionId) return
    const result = selectRelaysByRegion(regionId)
    if (result.nearestRelays) {
      setRecommendedRelays(result.nearestRelays)
      setLocationInfo(result.region)
    }
  }

  // Finish setup and publish relay list
  const handleFinishSetup = async () => {
    setLoading(true)
    try {
      if (recommendedRelays.length > 0) {
        // Publish NIP-65 relay list
        await publishRelayListMetadata(recommendedRelays.map(r => ({
          url: r.url,
          read: true,
          write: true
        })))
      }

      // Save pubkey and complete
      savePubkey(createdPubkey)
      localStorage.setItem('nurunuru_login_method', 'nosskey')
      setStep('success')
    } catch (e) {
      console.error('Setup finish error:', e)
      // Even if relay setup fails, we can proceed to success as the key is created
      savePubkey(createdPubkey)
      localStorage.setItem('nurunuru_login_method', 'nosskey')
      setStep('success')
    } finally {
      setLoading(false)
    }
  }

  const handleComplete = () => {
    onSuccess(createdPubkey)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay p-4" onClick={onClose}>
      <div className="w-full max-w-md bg-[var(--bg-primary)] rounded-3xl overflow-hidden shadow-2xl animate-scaleIn" onClick={e => e.stopPropagation()}>

        {/* Progress bar */}
        <div className="h-1.5 w-full bg-[var(--bg-secondary)] flex">
          <div className={`h-full bg-[var(--line-green)] transition-all duration-500 ${
            step === 'welcome' ? 'w-1/4' : step === 'relay' ? 'w-3/4' : 'w-full'
          }`} />
        </div>

        <div className="p-8">
          {step === 'welcome' && (
            <div className="text-center space-y-6">
              <div className="w-20 h-20 mx-auto bg-green-500/10 rounded-full flex items-center justify-center">
                <svg className="w-10 h-10 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M16 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2" />
                  <circle cx="8.5" cy="7" r="4" />
                  <line x1="20" y1="8" x2="20" y2="14" />
                  <line x1="17" y1="11" x2="23" y2="11" />
                </svg>
              </div>
              <div>
                <h2 className="text-2xl font-bold text-[var(--text-primary)] mb-2">新規登録</h2>
                <p className="text-[var(--text-secondary)] text-sm">
                  パスキーを使用して、新しいNostrアカウントを作成します。秘密鍵はデバイス内に安全に保管されます。
                </p>
              </div>

              {error && (
                <div className="p-3 bg-red-500/10 rounded-xl">
                  <p className="text-red-500 text-xs">{error}</p>
                </div>
              )}

              <button
                onClick={handleCreateAccount}
                disabled={loading}
                className="w-full btn-line py-4 text-lg font-bold disabled:opacity-50"
              >
                {loading ? '作成中...' : 'アカウントを作成する'}
              </button>

              <button onClick={onClose} className="text-[var(--text-tertiary)] text-sm hover:underline">
                キャンセル
              </button>
            </div>
          )}

          {step === 'relay' && (
            <div className="space-y-5">
              <div className="text-center">
                <div className="w-14 h-14 mx-auto bg-blue-500/10 rounded-full flex items-center justify-center mb-3">
                  <svg className="w-7 h-7 text-blue-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0118 0z" />
                    <circle cx="12" cy="10" r="3" />
                  </svg>
                </div>
                <h2 className="text-xl font-bold text-[var(--text-primary)] mb-1">リレーのセットアップ</h2>
                <p className="text-[var(--text-secondary)] text-xs">
                  地域を選択すると最適なリレーが自動設定されます。
                </p>
              </div>

              <div className="space-y-4">
                {/* Selection Mode Tabs */}
                <div className="flex p-1 bg-[var(--bg-secondary)] rounded-xl">
                  <button
                    onClick={() => {
                      setSelectionMode('auto')
                      startRelayDetection()
                    }}
                    className={`flex-1 py-2 text-xs font-bold rounded-lg transition-colors ${
                      selectionMode === 'auto' ? 'bg-[var(--bg-primary)] text-[var(--line-green)] shadow-sm' : 'text-[var(--text-tertiary)]'
                    }`}
                  >
                    GPSで自動検出
                  </button>
                  <button
                    onClick={() => setSelectionMode('manual')}
                    className={`flex-1 py-2 text-xs font-bold rounded-lg transition-colors ${
                      selectionMode === 'manual' ? 'bg-[var(--bg-primary)] text-[var(--line-green)] shadow-sm' : 'text-[var(--text-tertiary)]'
                    }`}
                  >
                    手動で地域を選択
                  </button>
                </div>

                {selectionMode === 'manual' && (
                  <div className="animate-fadeIn">
                    <select
                      onChange={(e) => handleRegionSelect(e.target.value)}
                      className="w-full bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)]"
                      defaultValue=""
                    >
                      <option value="" disabled>地域を選択してください...</option>
                      {REGION_COORDINATES.map(region => (
                        <option key={region.id} value={region.id}>
                          {region.country === 'JP' ? '🇯🇵 ' : ''}{region.name}
                        </option>
                      ))}
                    </select>
                  </div>
                )}

                <div className="bg-[var(--bg-secondary)] rounded-2xl p-4 space-y-3 max-h-40 overflow-y-auto border border-[var(--border-color)]">
                  {loading ? (
                    <div className="py-6 text-center space-y-2">
                      <div className="w-5 h-5 border-2 border-[var(--line-green)] border-t-transparent rounded-full animate-spin mx-auto"></div>
                      <p className="text-[10px] text-[var(--text-tertiary)]">最適なリレーを検索中...</p>
                    </div>
                  ) : recommendedRelays.length > 0 ? (
                    <>
                      <div className="flex items-center justify-between text-[10px] font-bold text-[var(--text-tertiary)] px-1 border-b border-[var(--border-color)] pb-2 mb-1">
                        <span>推奨リレー ({locationInfo?.name || '選択済み'})</span>
                        <span>距離</span>
                      </div>
                      {recommendedRelays.map((relay, i) => (
                        <div key={i} className="flex items-center justify-between text-xs">
                          <span className="text-[var(--text-primary)] truncate flex-1">{relay.url.replace('wss://', '')}</span>
                          <span className="text-[var(--text-tertiary)] text-[10px] ml-2 font-mono">
                            {relay.distance ? formatDistance(relay.distance) : '-'}
                          </span>
                        </div>
                      ))}
                    </>
                  ) : (
                    <div className="py-6 text-center">
                      <p className="text-xs text-[var(--text-tertiary)]">地域を選択してください</p>
                    </div>
                  )}
                </div>
              </div>

              <button
                onClick={handleFinishSetup}
                disabled={loading}
                className="w-full btn-line py-4 text-lg font-bold disabled:opacity-50"
              >
                {loading ? '設定中...' : 'セットアップを完了する'}
              </button>
            </div>
          )}

          {step === 'success' && (
            <div className="text-center space-y-6 animate-fadeIn">
              <div className="w-20 h-20 mx-auto bg-green-500 rounded-full flex items-center justify-center shadow-lg shadow-green-500/20">
                <svg className="w-12 h-12 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
              </div>
              <div>
                <h2 className="text-2xl font-bold text-[var(--text-primary)] mb-2">準備完了！</h2>
                <p className="text-[var(--text-secondary)] text-sm">
                  アカウントが作成されました。ぬるぬるの世界へようこそ！
                </p>
              </div>

              <div className="bg-[var(--bg-secondary)] rounded-2xl p-4 text-left">
                <p className="text-[var(--text-tertiary)] text-xs mb-1">あなたの公開鍵 (npub)</p>
                <p className="text-[var(--text-primary)] text-xs font-mono break-all line-clamp-2">
                  {createdPubkey ? nip19.npubEncode(createdPubkey) : ''}
                </p>
              </div>

              <button
                onClick={handleComplete}
                className="w-full btn-line py-4 text-lg font-bold"
              >
                はじめる
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
