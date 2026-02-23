'use client'

import { useState, useEffect, useRef } from 'react'
import { nip19, getPublicKey } from 'nostr-tools'
import {
  savePubkey,
  setStoredPrivateKey,
  publishRelayListMetadata,
  setDefaultRelay,
  hexToBytes,
  uploadImage,
  createEventTemplate,
  signEventNip07,
  publishEvent
} from '@/lib/nostr'
import { autoDetectRelays, formatDistance, REGION_COORDINATES, selectRelaysByRegion, saveSelectedRegion } from '@/lib/geohash'

/**
 * SignUpModal Component
 *
 * Handles new user registration including:
 * 1. Passkey registration via Nosskey
 * 2. Private key backup & Nostr key derivation
 * 3. Automatic relay setup based on geolocation
 * 4. Profile setup and metadata publishing
 */
export default function SignUpModal({ onClose, onSuccess, nosskeyManager }) {
  const [step, setStep] = useState('welcome') // welcome, backup, relay, profile, success
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [createdPubkey, setCreatedPubkey] = useState(null)
  const [credentialId, setCredentialId] = useState(null)
  const [backupNsec, setBackupNsec] = useState('')
  const [nsecCopied, setNsecCopied] = useState(false)
  const [recommendedRelays, setRecommendedRelays] = useState([])
  const [locationInfo, setLocationInfo] = useState(null)
  const [selectionMode, setSelectionMode] = useState('auto') // auto, manual

  // Profile setup state
  const [profileForm, setProfileForm] = useState({
    name: '',
    about: '',
    picture: '',
    banner: '',
    nip05: '',
    lud16: '',
    website: '',
    birthday: ''
  })
  const [uploadingPicture, setUploadingPicture] = useState(false)
  const [uploadingBanner, setUploadingBanner] = useState(false)
  const pictureInputRef = useRef(null)
  const bannerInputRef = useRef(null)

  // Handle account creation (Passkey step)
  const handleCreateAccount = async () => {
    setLoading(true)
    setError('')
    try {
      if (!nosskeyManager) throw new Error('Passkey manager not initialized')

      // Ensure manager is available globally
      window.nosskeyManager = nosskeyManager

      // 1. Create passkey - This triggers the FIRST biometric prompt
      const cid = await nosskeyManager.createPasskey({
        rp: { name: 'ぬるぬる' },
        user: { name: 'user', displayName: 'Nostr User' }
      })

      if (cid) {
        setCredentialId(cid)
        setStep('backup')
      } else {
        throw new Error('パスキーの作成に失敗しました')
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

  // Handle Private Key Backup (Nostr Key derivation step)
  const handleBackupKey = async () => {
    setLoading(true)
    setError('')
    try {
      // 2. Export private key - This triggers the SECOND biometric prompt
      // We use the credentialId from the previous step
      const privateKeyHex = await nosskeyManager.exportNostrKey(null, credentialId)

      if (privateKeyHex) {
        // Derive public key from the exported private key
        const pk = getPublicKey(hexToBytes(privateKeyHex))
        setCreatedPubkey(pk)

        // Construct key info for Nosskey SDK
        const keyInfo = {
          credentialId: nosskeyManager.constructor.bytesToHex ?
            nosskeyManager.constructor.bytesToHex(credentialId) :
            Array.from(credentialId).map(b => b.toString(16).padStart(2, '0')).join(''),
          pubkey: pk,
          salt: '6e6f7374722d6b6579' // Default salt used in SDK
        }

        // Update manager and storage
        nosskeyManager.setCurrentKeyInfo(keyInfo)
        setStoredPrivateKey(pk, privateKeyHex)

        // Set nsec for display
        setBackupNsec(nip19.nsecEncode(hexToBytes(privateKeyHex)))
      } else {
        throw new Error('秘密鍵のエクスポートに失敗しました')
      }
    } catch (e) {
      console.error('Backup error:', e)
      if (e.name === 'NotAllowedError') {
        setError('認証がキャンセルされました')
      } else {
        setError(e.message || '秘密鍵の生成に失敗しました')
      }
    } finally {
      setLoading(false)
    }
  }

  const handleCopyNsec = async () => {
    try {
      await navigator.clipboard.writeText(backupNsec)
      setNsecCopied(true)
      setTimeout(() => setNsecCopied(false), 2000)
    } catch (e) {
      console.error('Copy failed:', e)
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

        // Save detected region ID for persistence in Mini App settings
        if (result.region?.id) {
          saveSelectedRegion(result.region.id)
        }

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

  const handlePictureUpload = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    setUploadingPicture(true)
    try {
      const url = await uploadImage(file)
      setProfileForm(prev => ({ ...prev, picture: url }))
    } catch (err) {
      console.error('Upload failed:', err)
      alert('アップロードに失敗しました')
    } finally {
      setUploadingPicture(false)
    }
  }

  const handleBannerUpload = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    setUploadingBanner(true)
    try {
      const url = await uploadImage(file)
      setProfileForm(prev => ({ ...prev, banner: url }))
    } catch (err) {
      console.error('Upload failed:', err)
      alert('アップロードに失敗しました')
    } finally {
      setUploadingBanner(false)
    }
  }

  // Finish setup and publish relay list + profile
  const handleFinishSetup = async () => {
    setLoading(true)

    // 1. Basic persistence (must happen even if publish fails)
    savePubkey(createdPubkey)
    localStorage.setItem('nurunuru_login_method', 'nosskey')

    if (recommendedRelays.length > 0) {
      // Set the first relay as default for the application immediately
      if (recommendedRelays[0]?.url) {
        setDefaultRelay(recommendedRelays[0].url)
      }

      // Ensure region is saved for Mini App persistence
      if (locationInfo?.id) {
        saveSelectedRegion(locationInfo.id)
      }
    }

    try {
      const targetRelays = recommendedRelays.length > 0
        ? recommendedRelays.map(r => r.url)
        : [setDefaultRelay()]

      // 2. Publish Profile Metadata (kind 0) and Relay List (kind 10002)
      // Both use the cached key from the backup step
      const profileData = {
        name: profileForm.name || 'Anonymous',
        display_name: profileForm.name || 'Anonymous',
        about: profileForm.about,
        picture: profileForm.picture,
        banner: profileForm.banner,
        nip05: profileForm.nip05,
        lud16: profileForm.lud16,
        website: profileForm.website,
        birthday: profileForm.birthday
      }

      const profileEvent = createEventTemplate(0, JSON.stringify(profileData))
      profileEvent.pubkey = createdPubkey

      // Use signEventNip07 which will use the cached private key
      const signedProfile = await signEventNip07(profileEvent)
      if (signedProfile) {
        await publishEvent(signedProfile, targetRelays)
      }

      // 3. Publish NIP-65 relay list
      if (recommendedRelays.length > 0) {
        await publishRelayListMetadata(recommendedRelays.map(r => ({
          url: r.url,
          read: true,
          write: true
        })))
      }

      setStep('success')
    } catch (e) {
      console.error('Setup publication failed, but account is created:', e)
      // Proceed to success anyway
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
            step === 'welcome' ? 'w-1/5' :
            step === 'backup' ? 'w-2/5' :
            step === 'relay' ? 'w-3/5' :
            step === 'profile' ? 'w-4/5' : 'w-full'
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
                  パスキーを使用して、新しいNostrアカウントを作成します。
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

          {step === 'backup' && (
            <div className="text-center space-y-6 animate-fadeIn">
              <div className="w-20 h-20 mx-auto bg-orange-500/10 rounded-full flex items-center justify-center">
                <svg className="w-10 h-10 text-orange-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
                </svg>
              </div>
              <div>
                <h2 className="text-2xl font-bold text-[var(--text-primary)] mb-2">秘密鍵のバックアップ</h2>
                <p className="text-[var(--text-secondary)] text-sm">
                  アカウントを復旧するために必要な「秘密鍵」を生成します。この鍵は誰にも教えないでください。
                </p>
              </div>

              {!backupNsec ? (
                <div className="space-y-4">
                  {error && (
                    <div className="p-3 bg-red-500/10 rounded-xl">
                      <p className="text-red-500 text-xs">{error}</p>
                    </div>
                  )}
                  <button
                    onClick={handleBackupKey}
                    disabled={loading}
                    className="w-full btn-line py-4 text-lg font-bold disabled:opacity-50"
                  >
                    {loading ? '生成中...' : '秘密鍵を発行する'}
                  </button>
                </div>
              ) : (
                <div className="space-y-4">
                  <div className="bg-[var(--bg-secondary)] rounded-2xl p-4 text-left border border-orange-500/30">
                    <p className="text-orange-500 text-[10px] font-bold mb-1 uppercase">あなたの秘密鍵 (nsec) - 大切に保管してください</p>
                    <p className="text-[var(--text-primary)] text-xs font-mono break-all line-clamp-3 bg-black/20 p-2 rounded">
                      {backupNsec}
                    </p>
                    <button
                      onClick={handleCopyNsec}
                      className="mt-2 w-full py-2 bg-[var(--bg-tertiary)] rounded-xl text-sm font-bold flex items-center justify-center gap-2"
                    >
                      {nsecCopied ? (
                        <>
                          <svg className="w-4 h-4 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <polyline points="20 6 9 17 4 12"/>
                          </svg>
                          コピーしました
                        </>
                      ) : (
                        <>
                          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                            <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/>
                          </svg>
                          秘密鍵をコピー
                        </>
                      )}
                    </button>
                  </div>
                  <button
                    onClick={() => {
                      setStep('relay')
                      startRelayDetection()
                    }}
                    className="w-full btn-line py-4 text-lg font-bold"
                  >
                    次へ進む
                  </button>
                </div>
              )}
            </div>
          )}

          {step === 'relay' && (
            <div className="space-y-5 animate-fadeIn">
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
                onClick={() => setStep('profile')}
                disabled={recommendedRelays.length === 0}
                className="w-full btn-line py-4 text-lg font-bold disabled:opacity-50"
              >
                次へ進む
              </button>
            </div>
          )}

          {step === 'profile' && (
            <div className="space-y-5 animate-fadeIn">
              <div className="text-center">
                <div className="w-14 h-14 mx-auto bg-green-500/10 rounded-full flex items-center justify-center mb-3">
                  <svg className="w-7 h-7 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2" />
                    <circle cx="12" cy="7" r="4" />
                  </svg>
                </div>
                <h2 className="text-xl font-bold text-[var(--text-primary)] mb-1">プロフィールの設定</h2>
                <p className="text-[var(--text-secondary)] text-xs">
                  あなたの情報を入力して、世界に公開しましょう。
                </p>
              </div>

              <div className="space-y-4 max-h-[50vh] overflow-y-auto px-1">
                <div>
                  <label className="block text-xs font-bold text-[var(--text-tertiary)] mb-1 uppercase">名前</label>
                  <input
                    type="text"
                    value={profileForm.name}
                    onChange={(e) => setProfileForm({...profileForm, name: e.target.value})}
                    className="w-full bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)]"
                    placeholder="表示名"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold text-[var(--text-tertiary)] mb-1 uppercase">アイコン画像</label>
                  <div className="flex gap-2">
                    <input
                      type="url"
                      value={profileForm.picture}
                      onChange={(e) => setProfileForm({...profileForm, picture: e.target.value})}
                      className="flex-1 bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)]"
                      placeholder="https://..."
                    />
                    <input ref={pictureInputRef} type="file" accept="image/*" onChange={handlePictureUpload} className="hidden" />
                    <button type="button" onClick={() => pictureInputRef.current?.click()} disabled={uploadingPicture} className="bg-[var(--bg-secondary)] px-4 rounded-xl flex-shrink-0">
                      {uploadingPicture ? <div className="w-5 h-5 border-2 border-[var(--text-tertiary)] border-t-transparent rounded-full animate-spin" /> :
                      <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>}
                    </button>
                  </div>
                </div>

                <div>
                  <label className="block text-xs font-bold text-[var(--text-tertiary)] mb-1 uppercase">自己紹介</label>
                  <textarea
                    value={profileForm.about}
                    onChange={(e) => setProfileForm({...profileForm, about: e.target.value})}
                    className="w-full bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)] h-20 resize-none"
                    placeholder="自己紹介"
                  />
                </div>

                <details className="group">
                  <summary className="text-xs text-[var(--line-green)] cursor-pointer font-bold mb-2">詳細設定を表示</summary>
                  <div className="space-y-4 pt-2">
                    <div>
                      <label className="block text-xs font-bold text-[var(--text-tertiary)] mb-1 uppercase">バナー画像</label>
                      <div className="flex gap-2">
                        <input
                          type="url"
                          value={profileForm.banner}
                          onChange={(e) => setProfileForm({...profileForm, banner: e.target.value})}
                          className="flex-1 bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)]"
                          placeholder="https://..."
                        />
                        <input ref={bannerInputRef} type="file" accept="image/*" onChange={handleBannerUpload} className="hidden" />
                        <button type="button" onClick={() => bannerInputRef.current?.click()} disabled={uploadingBanner} className="bg-[var(--bg-secondary)] px-4 rounded-xl flex-shrink-0">
                          {uploadingBanner ? <div className="w-5 h-5 border-2 border-[var(--text-tertiary)] border-t-transparent rounded-full animate-spin" /> :
                          <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>}
                        </button>
                      </div>
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-[var(--text-tertiary)] mb-1 uppercase">NIP-05 (認証)</label>
                      <input type="text" value={profileForm.nip05} onChange={(e) => setProfileForm({...profileForm, nip05: e.target.value})} className="w-full bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)]" placeholder="user@domain.com" />
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-[var(--text-tertiary)] mb-1 uppercase">ライトニングアドレス</label>
                      <input type="text" value={profileForm.lud16} onChange={(e) => setProfileForm({...profileForm, lud16: e.target.value})} className="w-full bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)]" placeholder="you@wallet.com" />
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-[var(--text-tertiary)] mb-1 uppercase">ウェブサイト</label>
                      <input type="url" value={profileForm.website} onChange={(e) => setProfileForm({...profileForm, website: e.target.value})} className="w-full bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)]" placeholder="https://..." />
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-[var(--text-tertiary)] mb-1 uppercase">誕生日</label>
                      <input type="text" value={profileForm.birthday} onChange={(e) => setProfileForm({...profileForm, birthday: e.target.value})} className="w-full bg-[var(--bg-secondary)] border-none rounded-xl px-4 py-3 text-sm text-[var(--text-primary)] focus:ring-2 focus:ring-[var(--line-green)]" placeholder="MM-DD" />
                    </div>
                  </div>
                </details>
              </div>

              <button
                onClick={handleFinishSetup}
                disabled={loading}
                className="w-full btn-line py-4 text-lg font-bold disabled:opacity-50"
              >
                {loading ? '保存中...' : 'セットアップを完了する'}
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
