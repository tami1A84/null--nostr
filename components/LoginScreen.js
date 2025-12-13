'use client'

import { useState, useEffect, useRef } from 'react'
import { nip19 } from 'nostr-tools'
import { hasNip07, getNip07PublicKey, savePubkey } from '@/lib/nostr'

export default function LoginScreen({ onLogin }) {
  const [checking, setChecking] = useState(true)
  const [hasExtension, setHasExtension] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [error, setError] = useState('')
  
  // Nosskey states
  const [nosskeySupported, setNosskeySupported] = useState(false)
  const [nosskeyLoading, setNosskeyLoading] = useState(false)
  const [showExtensionOption, setShowExtensionOption] = useState(false)
  
  // Onboarding states
  const [showOnboarding, setShowOnboarding] = useState(false)
  const [onboardingStep, setOnboardingStep] = useState(0)
  const [createdPubkey, setCreatedPubkey] = useState(null)
  const [createdNsec, setCreatedNsec] = useState(null)
  const [nsecCopied, setNsecCopied] = useState(false)
  const [nsecConfirmed, setNsecConfirmed] = useState(false)
  
  const nosskeyManagerRef = useRef(null)

  useEffect(() => {
    const init = async () => {
      await new Promise(r => setTimeout(r, 500))
      setHasExtension(hasNip07())
      
      try {
        const { NosskeyManager } = await import('nosskey-sdk')
        const manager = new NosskeyManager({
          storageOptions: { enabled: true, storageKey: 'nurunuru_nosskey' },
          cacheOptions: { enabled: true, timeoutMs: 300000 } // 5 minute cache
        })
        nosskeyManagerRef.current = manager
        
        // Always enable - actual support checked during operation
        setNosskeySupported(true)
      } catch (e) {
        console.log('Nosskey SDK not available:', e)
        setNosskeySupported(false)
      }
      
      setChecking(false)
    }
    init()
  }, [])

  const handleNip07Connect = async () => {
    setError('')
    setConnecting(true)
    
    try {
      const pubkey = await getNip07PublicKey()
      savePubkey(pubkey)
      localStorage.setItem('nurunuru_login_method', 'nip07')
      onLogin(pubkey)
    } catch (e) {
      setError(e.message || '接続に失敗しました')
    } finally {
      setConnecting(false)
    }
  }

  // Helper functions
  const hexToBytes = (hex) => {
    const bytes = new Uint8Array(hex.length / 2)
    for (let i = 0; i < hex.length; i += 2) {
      bytes[i / 2] = parseInt(hex.substr(i, 2), 16)
    }
    return bytes
  }

  const bytesToHex = (bytes) => {
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('')
  }

  // Convert base64url to ArrayBuffer
  const base64urlToArrayBuffer = (base64url) => {
    let base64 = base64url.replace(/-/g, '+').replace(/_/g, '/')
    while (base64.length % 4) {
      base64 += '='
    }
    const binary = atob(base64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i)
    }
    return bytes.buffer
  }

  // Nosskey: Create new account with passkey
  const handleNosskeyCreate = async () => {
    setError('')
    setNosskeyLoading(true)
    
    try {
      const manager = nosskeyManagerRef.current
      if (!manager) throw new Error('Nosskey not initialized')
      
      // Create passkey and derive Nostr key from PRF
      const credentialId = await manager.createPasskey({
        rp: { name: 'ぬるぬる Nostr Client' },
        user: { name: 'nostr-user', displayName: 'Nostr User' },
        pubKeyCredParams: [
          { type: 'public-key', alg: -7 },   // ES256
          { type: 'public-key', alg: -257 }  // RS256
        ],
        authenticatorSelection: {
          authenticatorAttachment: 'platform',
          residentKey: 'preferred',
          userVerification: 'preferred'
        }
      })
      
      // Derive Nostr key from PRF output
      const keyInfo = await manager.createNostrKey(credentialId)
      manager.setCurrentKeyInfo(keyInfo)
      
      const pubkey = await manager.getPublicKey()
      
      // Try to export nsec for backup and DM functionality
      // Note: Don't pass credentialId, let SDK use the one from keyInfo
      let nsec = null
      let privateKeyHex = null
      try {
        privateKeyHex = await manager.exportNostrKey(keyInfo)
        if (privateKeyHex) {
          nsec = nip19.nsecEncode(hexToBytes(privateKeyHex))
          // Store for DM functionality and auto-signing
          window.nostrPrivateKey = privateKeyHex
        }
      } catch (exportError) {
        console.log('Could not export nsec:', exportError)
      }
      
      setCreatedPubkey(pubkey)
      setCreatedNsec(nsec)
      window.nosskeyManager = manager
      
      // Show onboarding
      setShowOnboarding(true)
      setOnboardingStep(0)
      setNsecCopied(false)
      setNsecConfirmed(false)
    } catch (e) {
      console.error('Nosskey create error:', e)
      if (e.name === 'NotSupportedError' || e.message?.includes('PRF') || e.message?.includes('prf')) {
        setError('このブラウザはパスキーに対応していません。拡張機能をご利用ください。')
      } else if (e.name === 'NotAllowedError') {
        setError('パスキーの作成がキャンセルされました')
      } else {
        setError(e.message || 'アカウント作成に失敗しました')
      }
    } finally {
      setNosskeyLoading(false)
    }
  }

  // Nosskey: Login with existing passkey
  const handleNosskeyLogin = async () => {
    setError('')
    setNosskeyLoading(true)
    
    try {
      const manager = nosskeyManagerRef.current
      if (!manager) throw new Error('Nosskey not initialized')
      
      // Enable key caching for the session
      manager.setCacheOptions({ enabled: true, timeoutMs: 3600000 }) // 1 hour cache
      
      // Try to authenticate with existing passkey and derive key
      // Don't pass credentialId - let user select their passkey
      const keyInfo = await manager.createNostrKey()
      manager.setCurrentKeyInfo(keyInfo)
      
      const pubkey = await manager.getPublicKey()
      
      // Store manager for later use
      window.nosskeyManager = manager
      
      // Try to export private key for auto-signing (uses cached auth, no extra prompt)
      try {
        const privateKeyHex = await manager.exportNostrKey(keyInfo)
        if (privateKeyHex) {
          window.nostrPrivateKey = privateKeyHex
        }
      } catch (exportError) {
        // This is okay - user can export later in settings
        console.log('Private key export skipped:', exportError.message)
      }
      
      savePubkey(pubkey)
      localStorage.setItem('nurunuru_login_method', 'nosskey')
      
      onLogin(pubkey)
    } catch (e) {
      console.error('Nosskey login error:', e)
      
      if (e.name === 'NotAllowedError') {
        // User cancelled or no passkey found
        setError('パスキーが見つかりませんでした。新規登録してください。')
      } else if (e.name === 'NotSupportedError' || e.message?.includes('PRF') || e.message?.includes('prf')) {
        setError('このブラウザはパスキーに対応していません。拡張機能をご利用ください。')
      } else {
        setError('ログインに失敗しました: ' + (e.message || '不明なエラー'))
      }
    } finally {
      setNosskeyLoading(false)
    }
  }

  // Copy nsec to clipboard
  const handleCopyNsec = async () => {
    if (!createdNsec) return
    try {
      await navigator.clipboard.writeText(createdNsec)
      setNsecCopied(true)
      setTimeout(() => setNsecCopied(false), 3000)
    } catch (e) {
      const textarea = document.createElement('textarea')
      textarea.value = createdNsec
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      setNsecCopied(true)
      setTimeout(() => setNsecCopied(false), 3000)
    }
  }

  // Complete onboarding and login
  const completeOnboarding = () => {
    savePubkey(createdPubkey)
    localStorage.setItem('nurunuru_login_method', 'nosskey')
    onLogin(createdPubkey)
  }

  // Onboarding screens
  if (showOnboarding) {
    const steps = createdNsec ? [
      {
        title: 'アカウント作成完了！',
        icon: (
          <svg className="w-16 h-16 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M22 11.08V12a10 10 0 11-5.93-9.14" strokeLinecap="round"/>
            <polyline points="22 4 12 14.01 9 11.01" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        ),
        content: (
          <div className="text-center">
            <p className="text-[var(--text-secondary)] mb-4">
              パスキーでNostrアカウントが作成されました。
            </p>
            <p className="text-xs text-[var(--text-tertiary)]">
              Face ID / Touch ID / Windows Hello で<br/>安全にログインできます
            </p>
          </div>
        ),
        canProceed: true
      },
      {
        title: '🔑 秘密鍵のバックアップ',
        icon: (
          <svg className="w-16 h-16 text-yellow-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/>
            <line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
        ),
        content: (
          <div className="space-y-4">
            <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-3 rounded-xl">
              <p className="text-sm text-red-600 dark:text-red-400 font-medium mb-1">
                ⚠️ 重要：この画面でのみ表示されます
              </p>
              <p className="text-xs text-red-500 dark:text-red-400">
                秘密鍵は自動保存されません。必ず安全な場所に保管してください。
              </p>
            </div>
            
            <div className="bg-[var(--bg-tertiary)] p-3 rounded-xl">
              <p className="text-xs text-[var(--text-tertiary)] mb-2">あなたの秘密鍵 (nsec)</p>
              <div className="bg-[var(--bg-primary)] p-2 rounded-lg border border-[var(--border-color)]">
                <p className="text-xs font-mono text-[var(--text-primary)] break-all select-all">
                  {createdNsec}
                </p>
              </div>
              <button
                onClick={handleCopyNsec}
                className={`mt-2 w-full py-2 rounded-lg text-sm font-medium transition-all ${
                  nsecCopied 
                    ? 'bg-[var(--line-green)] text-white' 
                    : 'bg-[var(--bg-secondary)] text-[var(--text-primary)] hover:bg-[var(--border-color)]'
                }`}
              >
                {nsecCopied ? '✓ コピーしました' : 'クリップボードにコピー'}
              </button>
            </div>

            <label className="flex items-start gap-3 p-3 bg-[var(--bg-secondary)] rounded-xl cursor-pointer">
              <input
                type="checkbox"
                checked={nsecConfirmed}
                onChange={(e) => setNsecConfirmed(e.target.checked)}
                className="mt-0.5 w-5 h-5 rounded border-[var(--border-color)] text-[var(--line-green)] focus:ring-[var(--line-green)]"
              />
              <span className="text-sm text-[var(--text-secondary)]">
                秘密鍵を安全な場所に保管しました
              </span>
            </label>
          </div>
        ),
        canProceed: nsecConfirmed
      },
      {
        title: 'DM機能について',
        icon: (
          <svg className="w-16 h-16 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/>
          </svg>
        ),
        content: (
          <div className="space-y-3">
            <div className="bg-[var(--line-green)] bg-opacity-10 border border-[var(--line-green)] border-opacity-30 p-3 rounded-xl">
              <p className="text-sm text-[var(--line-green)] font-medium flex items-center gap-2">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M22 11.08V12a10 10 0 11-5.93-9.14" strokeLinecap="round"/>
                  <polyline points="22 4 12 14.01 9 11.01" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
                DM機能が有効です
              </p>
            </div>
            <p className="text-[var(--text-secondary)] text-sm">
              秘密鍵を使用してDMの送受信ができます。
            </p>
            <div className="bg-[var(--bg-tertiary)] p-3 rounded-lg text-sm">
              <p className="font-medium text-[var(--text-primary)] mb-2">💡 他のアプリでも使うには</p>
              <p className="text-xs text-[var(--text-secondary)]">
                保存した秘密鍵をAlby等の拡張機能にインポートしてください。
              </p>
            </div>
          </div>
        ),
        canProceed: true
      }
    ] : [
      // Simplified onboarding when nsec export failed
      {
        title: 'アカウント作成完了！',
        icon: (
          <svg className="w-16 h-16 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M22 11.08V12a10 10 0 11-5.93-9.14" strokeLinecap="round"/>
            <polyline points="22 4 12 14.01 9 11.01" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        ),
        content: (
          <div className="text-center space-y-4">
            <p className="text-[var(--text-secondary)]">
              パスキーでNostrアカウントが作成されました。
            </p>
            <div className="bg-[var(--bg-tertiary)] p-3 rounded-xl text-left">
              <p className="text-xs text-[var(--text-tertiary)] mb-1">次回からは</p>
              <p className="text-sm text-[var(--text-primary)]">
                Face ID / Touch ID でログインできます
              </p>
            </div>
            <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-3 rounded-xl text-left">
              <p className="text-xs text-yellow-700 dark:text-yellow-400">
                ⚠️ DM機能を使用するには「ミニアプリ」→「パスキー設定」から秘密鍵をエクスポートしてください
              </p>
            </div>
          </div>
        ),
        canProceed: true
      }
    ]

    const currentStep = steps[onboardingStep]
    
    return (
      <div className="min-h-screen flex flex-col bg-[var(--bg-primary)]">
        <div className="flex-1 flex flex-col items-center justify-center px-6 py-12">
          <div className="w-full max-w-sm">
            {/* Progress dots */}
            <div className="flex justify-center gap-2 mb-8">
              {steps.map((_, i) => (
                <div
                  key={i}
                  className={`w-2 h-2 rounded-full transition-all ${
                    i === onboardingStep ? 'bg-[var(--line-green)] w-6' : 'bg-[var(--border-color)]'
                  }`}
                />
              ))}
            </div>

            <div className="text-center mb-6">
              <div className="flex justify-center mb-4">{currentStep.icon}</div>
              <h2 className="text-xl font-bold text-[var(--text-primary)]">{currentStep.title}</h2>
            </div>
            
            <div className="mb-8">{currentStep.content}</div>
            
            <div className="flex gap-3">
              {onboardingStep > 0 && (
                <button
                  onClick={() => setOnboardingStep(prev => prev - 1)}
                  className="flex-1 btn-secondary py-3"
                >
                  戻る
                </button>
              )}
              {onboardingStep < steps.length - 1 ? (
                <button
                  onClick={() => setOnboardingStep(prev => prev + 1)}
                  disabled={!currentStep.canProceed}
                  className="flex-1 btn-line py-3 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  次へ
                </button>
              ) : (
                <button
                  onClick={completeOnboarding}
                  className="flex-1 btn-line py-3"
                >
                  はじめる
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    )
  }

  // Main login screen
  return (
    <div className="min-h-screen flex flex-col bg-[var(--bg-primary)]">
      <div className="flex-1 flex flex-col items-center justify-center px-6 py-12">
        {/* Logo */}
        <div className="mb-8 text-center animate-fadeIn">
          <div className="w-32 h-32 mx-auto mb-6 rounded-2xl overflow-hidden shadow-lg">
            <img src="/favicon-512.png" alt="ぬるぬる" className="w-full h-full object-cover" />
          </div>
          <h1 className="text-4xl font-bold text-[var(--text-primary)] tracking-tight">ぬるぬる</h1>
        </div>

        <div className="w-full max-w-sm space-y-4">
          {checking ? (
            <div className="text-center animate-fadeIn">
              <div className="w-8 h-8 border-2 border-[var(--line-green)] border-t-transparent rounded-full animate-spin mx-auto mb-3"/>
              <p className="text-[var(--text-secondary)] text-sm">確認中...</p>
            </div>
          ) : nosskeySupported ? (
            <div className="animate-slideUp space-y-3">
              {/* Primary buttons - Nosskey */}
              <button
                onClick={handleNosskeyCreate}
                disabled={nosskeyLoading}
                className="w-full btn-line text-base py-4 disabled:opacity-50"
              >
                {nosskeyLoading ? (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-5 h-5 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                      <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                    </svg>
                    処理中...
                  </span>
                ) : (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
                      <line x1="12" y1="12" x2="12" y2="18"/>
                      <line x1="9" y1="15" x2="15" y2="15"/>
                    </svg>
                    新規登録
                  </span>
                )}
              </button>
              
              <button
                onClick={handleNosskeyLogin}
                disabled={nosskeyLoading}
                className="w-full btn-secondary text-base py-4 disabled:opacity-50"
              >
                {nosskeyLoading ? (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-5 h-5 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                      <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                    </svg>
                    確認中...
                  </span>
                ) : (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
                      <circle cx="12" cy="15" r="1"/>
                    </svg>
                    ログイン
                  </span>
                )}
              </button>
              
              <p className="text-center text-xs text-[var(--text-tertiary)]">
                Face ID / Touch ID / Windows Hello
              </p>

              {/* Extension option - folded */}
              <div className="pt-4">
                <button
                  onClick={() => setShowExtensionOption(!showExtensionOption)}
                  className="w-full flex items-center justify-center gap-2 text-sm text-[var(--text-tertiary)] py-2"
                >
                  <span>既存アカウントでログイン</span>
                  <svg className={`w-4 h-4 transition-transform ${showExtensionOption ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polyline points="6 9 12 15 18 9"/>
                  </svg>
                </button>
                
                {showExtensionOption && (
                  <div className="mt-3 animate-fadeIn space-y-3">
                    <p className="text-xs text-[var(--text-secondary)] text-center">
                      既存のNostrアカウントをお持ちの方は<br/>拡張機能でログインしてください
                    </p>
                    {hasExtension ? (
                      <button
                        onClick={handleNip07Connect}
                        disabled={connecting}
                        className="w-full btn-secondary text-sm py-3 disabled:opacity-50"
                      >
                        {connecting ? '接続中...' : (
                          <span className="flex items-center justify-center gap-2">
                            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                              <rect x="3" y="11" width="18" height="11" rx="2"/>
                              <path d="M7 11V7a5 5 0 0110 0v4"/>
                            </svg>
                            Alby / nos2x でログイン
                          </span>
                        )}
                      </button>
                    ) : (
                      <div className="text-center p-3 bg-[var(--bg-secondary)] rounded-xl">
                        <p className="text-sm text-[var(--text-secondary)] mb-2">拡張機能が見つかりません</p>
                        <a href="https://getalby.com" target="_blank" rel="noopener noreferrer" className="text-xs text-[var(--line-green)] underline">
                          Alby を入手 →
                        </a>
                      </div>
                    )}
                  </div>
                )}
              </div>

              {error && (
                <div className="mt-2 p-3 rounded-lg bg-red-50 dark:bg-red-900/20 animate-scaleIn">
                  <p className="text-sm text-red-600 dark:text-red-400 text-center">{error}</p>
                </div>
              )}
            </div>
          ) : (
            /* No Nosskey SDK available */
            <div className="animate-slideUp space-y-3">
              <div className="p-4 bg-yellow-50 dark:bg-yellow-900/20 rounded-xl border border-yellow-200 dark:border-yellow-800 mb-4">
                <p className="text-sm text-yellow-700 dark:text-yellow-400 text-center">
                  パスキー機能を読み込めませんでした
                </p>
                <p className="text-xs text-yellow-600 dark:text-yellow-500 text-center mt-1">
                  拡張機能でログインしてください
                </p>
              </div>
              
              {hasExtension ? (
                <>
                  <button onClick={handleNip07Connect} disabled={connecting} className="w-full btn-line text-base py-4 disabled:opacity-50">
                    {connecting ? '接続中...' : '拡張機能でログイン'}
                  </button>
                  <p className="text-center text-xs text-[var(--text-tertiary)]">Alby / nos2x</p>
                </>
              ) : (
                <div className="card p-6 text-center">
                  <p className="text-sm text-[var(--text-secondary)] mb-4">拡張機能をインストールしてください。</p>
                  <a href="https://getalby.com" target="_blank" className="btn-line block py-3">Alby を入手</a>
                </div>
              )}
              {error && (
                <div className="p-3 rounded-lg bg-red-50 dark:bg-red-900/20">
                  <p className="text-sm text-red-600 dark:text-red-400 text-center">{error}</p>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="py-6 text-center animate-fadeIn">
        <p className="text-xs text-[var(--text-tertiary)]">Powered by Nostr</p>
      </div>
    </div>
  )
}
