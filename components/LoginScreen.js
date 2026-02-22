'use client'

import { useState, useEffect, useRef } from 'react'
import { nip19 } from 'nostr-tools'
import { savePubkey, setStoredPrivateKey } from '@/lib/nostr'

export default function LoginScreen({ onLogin }) {
  const [checking, setChecking] = useState(true)
  const [error, setError] = useState('')
  
  // Nosskey (passkey) states
  const [nosskeySupported, setNosskeySupported] = useState(false)
  const [nosskeyHasKey, setNosskeyHasKey] = useState(false)
  const [nosskeyLoading, setNosskeyLoading] = useState(false)
  const [showNostrLoginOption, setShowNostrLoginOption] = useState(false)
  const [showOnboarding, setShowOnboarding] = useState(false)
  const [onboardingStep, setOnboardingStep] = useState(0)
  const [createdPubkey, setCreatedPubkey] = useState(null)
  const nosskeyManagerRef = useRef(null)
  
  // nostr-login states
  const nostrLoginInitialized = useRef(false)
  const [nostrLoginReady, setNostrLoginReady] = useState(false)
  const [nostrLoginError, setNostrLoginError] = useState(false)

  // Initialize passkey support check
  useEffect(() => {
    const init = async () => {
      await new Promise(r => setTimeout(r, 300))
      
      try {
        const { NosskeyManager } = await import('nosskey-sdk')
        const manager = new NosskeyManager({
          storageOptions: { enabled: true, storageKey: 'nurunuru_nosskey' }
        })
        nosskeyManagerRef.current = manager
        
        const supported = await manager.isPrfSupported()
        console.log('PRF supported:', supported)
        setNosskeySupported(supported)
        
        if (supported) {
          // Check for stored key info
          const hasKey = manager.hasKeyInfo()
          console.log('Has stored key info:', hasKey)
          
          if (hasKey) {
            const keyInfo = manager.getCurrentKeyInfo()
            console.log('Stored key info:', keyInfo)
            
            // Support both old (publicKey) and new (pubkey) property names
            const storedPubkey = keyInfo?.pubkey || keyInfo?.publicKey
            if (storedPubkey) {
              console.log('Found valid pubkey:', storedPubkey)
              setNosskeyHasKey(true)
            } else {
              console.log('No valid pubkey in stored key info')
            }
          }
          
          // Also check localStorage directly as fallback
          if (!hasKey) {
            try {
              const storedData = localStorage.getItem('nurunuru_nosskey')
              console.log('Direct localStorage check:', storedData ? 'found' : 'not found')
              if (storedData) {
                const parsed = JSON.parse(storedData)
                console.log('Parsed stored data:', parsed)
                if (parsed?.pubkey || parsed?.publicKey) {
                  // Re-set the key info
                  manager.setCurrentKeyInfo(parsed)
                  setNosskeyHasKey(true)
                }
              }
            } catch (e) {
              console.log('Direct localStorage error:', e)
            }
          }
        }
      } catch (e) {
        console.log('Nosskey not supported:', e)
      }
      
      setChecking(false)
    }
    init()
  }, [])

  // Initialize nostr-login when needed
  useEffect(() => {
    const shouldInit = showNostrLoginOption || (!checking && !nosskeySupported)
    
    if (!shouldInit || nostrLoginInitialized.current) return
    
    const initNostrLogin = async () => {
      nostrLoginInitialized.current = true
      
      console.log('Initializing nostr-login...')
      
      try {
        const { init } = await import('nostr-login')

        await init({
          darkMode: true,
          title: 'ぬるぬる',
          description: 'Nostrクライアント',
          perms: 'sign_event:1,sign_event:4,sign_event:7,sign_event:9735,nip04_encrypt,nip04_decrypt,nip44_encrypt,nip44_decrypt',
          methods: ['extension', 'connect', 'readOnly', 'local'],
          noBanner: true
        })

        // Listen for login events
        document.addEventListener('nlAuth', (e) => {
          console.log('nlAuth event:', e.detail)
          if (e.detail.type === 'login' || e.detail.type === 'signup') {
            const pubkey = e.detail.pubkey
            if (pubkey) {
              savePubkey(pubkey)
              localStorage.setItem('nurunuru_login_method', 'nostr-login')
              onLogin(pubkey)
            }
          }
        })
        
        console.log('nostr-login ready')
        setNostrLoginReady(true)
      } catch (e) {
        console.error('nostr-login setup error:', e)
        setNostrLoginError(true)
      }
    }
    
    initNostrLogin()
  }, [showNostrLoginOption, checking, nosskeySupported, onLogin])

  // Launch nostr-login
  const handleNostrLoginLaunch = async () => {
    if (!nostrLoginReady) return
    console.log('Launching nostr-login')
    
    try {
      const { launch } = await import('nostr-login')
      await launch('welcome')
    } catch (e) {
      console.error('Failed to launch nostr-login:', e)
      setError('ログイン画面を開けませんでした')
    }
  }

  // Passkey create handler
  const handleNosskeyCreate = async () => {
    setNosskeyLoading(true)
    setError('')
    try {
      const manager = nosskeyManagerRef.current
      if (!manager) throw new Error('Manager not initialized')
      
      // First create a passkey
      const credentialId = await manager.createPasskey({
        rp: { name: 'ぬるぬる' },
        user: { name: 'user', displayName: 'Nostr User' }
      })
      
      // Then create the nostr key using the credential
      const result = await manager.createNostrKey(credentialId, {
        username: 'ぬるぬる'
      })
      
      if (result.pubkey) {
        // Store the key info
        manager.setCurrentKeyInfo(result)
        setCreatedPubkey(result.pubkey)
        setShowOnboarding(true)
      } else {
        setError('パスキーの作成に失敗しました')
      }
    } catch (e) {
      console.error('Nosskey create error:', e)
      if (e.name === 'NotAllowedError') {
        setError('パスキーの作成がキャンセルされました')
      } else {
        setError(e.message || 'パスキーの作成に失敗しました')
      }
    } finally {
      setNosskeyLoading(false)
    }
  }

  // Passkey login handler
  const handleNosskeyLogin = async () => {
    setNosskeyLoading(true)
    setError('')
    try {
      const manager = nosskeyManagerRef.current
      if (!manager) throw new Error('Manager not initialized')
      
      // First check for stored key info
      let keyInfo = manager.getCurrentKeyInfo()
      let storedPubkey = keyInfo?.pubkey || keyInfo?.publicKey
      
      console.log('Login - stored key info:', keyInfo)
      console.log('Login - stored pubkey:', storedPubkey)
      
      // If we have stored key info, use it
      if (keyInfo && storedPubkey) {
        savePubkey(storedPubkey)
        localStorage.setItem('nurunuru_login_method', 'nosskey')
        window.nosskeyManager = manager
        onLogin(storedPubkey)
        return
      }
      
      // No stored key info - try to authenticate with existing passkey
      // This allows login even if localStorage was cleared
      console.log('No stored key info, attempting passkey authentication...')
      
      try {
        // Call createNostrKey without credentialId to let browser select passkey
        const result = await manager.createNostrKey()
        console.log('Passkey auth result:', result)
        
        if (result && result.pubkey) {
          // Save the key info
          manager.setCurrentKeyInfo(result)
          savePubkey(result.pubkey)
          localStorage.setItem('nurunuru_login_method', 'nosskey')
          window.nosskeyManager = manager
          onLogin(result.pubkey)
        } else {
          setError('パスキーが見つかりません。新規登録してください。')
        }
      } catch (authError) {
        console.error('Passkey auth error:', authError)
        if (authError.name === 'NotAllowedError') {
          setError('認証がキャンセルされました')
        } else if (authError.message?.includes('No credentials')) {
          setError('パスキーが見つかりません。新規登録してください。')
        } else {
          setError('パスキーの認証に失敗しました')
        }
      }
    } catch (e) {
      console.error('Nosskey login error:', e)
      setError(e.message || 'ログインに失敗しました')
    } finally {
      setNosskeyLoading(false)
    }
  }

  // Complete onboarding after passkey creation
  const completeOnboarding = () => {
    if (createdPubkey) {
      savePubkey(createdPubkey)
      localStorage.setItem('nurunuru_login_method', 'nosskey')
      onLogin(createdPubkey)
    }
  }

  // Onboarding content
  const onboardingSteps = [
    {
      title: 'パスキーが作成されました！',
      description: 'Nostrでは公開鍵があなたのIDになります。この公開鍵を他の人に共有することで、フォローしてもらえます。',
      icon: (
        <svg className="w-16 h-16 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M22 11.08V12a10 10 0 11-5.93-9.14"/>
          <polyline points="22 4 12 14.01 9 11.01"/>
        </svg>
      )
    },
    {
      title: '秘密鍵は安全に保管',
      description: 'パスキーがあなたの秘密鍵を保護しています。Face IDやTouch IDで認証するたびに、安全に署名が行われます。',
      icon: (
        <svg className="w-16 h-16 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
          <path d="M7 11V7a5 5 0 0110 0v4"/>
        </svg>
      )
    }
  ]

  // Onboarding screen
  if (showOnboarding && createdPubkey) {
    const step = onboardingSteps[onboardingStep]
    const npub = nip19.npubEncode(createdPubkey)
    
    return (
      <div className="min-h-screen flex flex-col items-center justify-center p-6 bg-[var(--bg-primary)]">
        <div className="w-full max-w-md">
          <div className="card p-8 text-center animate-scaleIn">
            <div className="flex justify-center mb-6">{step.icon}</div>
            <h2 className="text-xl font-bold text-[var(--text-primary)] mb-3">{step.title}</h2>
            <p className="text-sm text-[var(--text-secondary)] mb-6">{step.description}</p>
            
            {onboardingStep === 0 && (
              <div className="mb-6">
                <p className="text-xs text-[var(--text-tertiary)] mb-2">あなたの公開鍵 (npub)</p>
                <div className="bg-[var(--bg-secondary)] rounded-lg p-3 break-all text-xs font-mono text-[var(--text-secondary)]">
                  {npub}
                </div>
              </div>
            )}
            
            <div className="flex gap-3">
              {onboardingStep > 0 && (
                <button
                  onClick={() => setOnboardingStep(s => s - 1)}
                  className="flex-1 btn-secondary py-3"
                >
                  戻る
                </button>
              )}
              {onboardingStep < onboardingSteps.length - 1 ? (
                <button
                  onClick={() => setOnboardingStep(s => s + 1)}
                  className="flex-1 btn-line py-3"
                >
                  次へ
                </button>
              ) : (
                <button onClick={completeOnboarding} className="flex-1 btn-line py-3">
                  始める
                </button>
              )}
            </div>
          </div>
          
          <div className="flex justify-center gap-2 mt-6">
            {onboardingSteps.map((_, i) => (
              <div
                key={i}
                className={`w-2 h-2 rounded-full transition-colors ${
                  i === onboardingStep ? 'bg-[var(--line-green)]' : 'bg-[var(--bg-tertiary)]'
                }`}
              />
            ))}
          </div>
        </div>
      </div>
    )
  }

  // Loading screen
  if (checking) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center p-6 bg-[var(--bg-primary)]">
        <div className="w-20 h-20 mb-6 animate-pulse">
          <img src="/nurunuru-star.png" alt="ぬるぬる" className="w-full h-full object-contain rounded-2xl" />
        </div>
        <p className="text-[var(--text-tertiary)] text-sm">読み込み中...</p>
      </div>
    )
  }

  // Main login screen
  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6 bg-[var(--bg-primary)]">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8 animate-fadeIn">
          <div className="w-28 h-28 mx-auto mb-4 rounded-3xl overflow-hidden shadow-lg">
            <img src="/nurunuru-star.png" alt="ぬるぬる" className="w-full h-full object-cover" />
          </div>
          <h1 className="text-3xl font-bold text-[var(--text-primary)]">ぬるぬる</h1>
        </div>

        {/* Login options */}
        <div className="space-y-4">
          {nosskeySupported ? (
            /* Passkey supported */
            <div className="animate-slideUp space-y-3">
              {nosskeyHasKey ? (
                /* Has existing passkey */
                <>
                  <button
                    onClick={handleNosskeyLogin}
                    disabled={nosskeyLoading}
                    className="w-full btn-line text-base py-4 disabled:opacity-50"
                  >
                    {nosskeyLoading ? (
                      <span className="flex items-center justify-center gap-2">
                        <svg className="w-5 h-5 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                          <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                        </svg>
                        認証中...
                      </span>
                    ) : (
                      <span className="flex items-center justify-center gap-2">
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
                          <circle cx="12" cy="15" r="1"/>
                        </svg>
                        パスキーでログイン
                      </span>
                    )}
                  </button>
                  <p className="text-center text-xs text-[var(--text-tertiary)]">
                    Face ID / Touch ID / Windows Hello
                  </p>
                </>
              ) : (
                /* No existing passkey */
                <>
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
                    <span className="flex items-center justify-center gap-2">
                      <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
                        <circle cx="12" cy="15" r="1"/>
                      </svg>
                      ログイン
                    </span>
                  </button>
                  
                  <p className="text-center text-xs text-[var(--text-tertiary)]">
                    Face ID / Touch ID / Windows Hello
                  </p>
                </>
              )}

              {/* Other login options - folded */}
              <div className="pt-4">
                <button
                  onClick={() => setShowNostrLoginOption(!showNostrLoginOption)}
                  className="w-full flex items-center justify-center gap-2 text-sm text-[var(--text-tertiary)] py-2"
                >
                  <span>その他のログイン方法</span>
                  <svg className={`w-4 h-4 transition-transform ${showNostrLoginOption ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polyline points="6 9 12 15 18 9"/>
                  </svg>
                </button>
                
                {showNostrLoginOption && (
                  <div className="mt-3 animate-fadeIn space-y-3">
                    {nostrLoginError ? (
                      <p className="text-center text-xs text-red-500">
                        読み込みに失敗しました。ページを再読み込みしてください。
                      </p>
                    ) : (
                      <button
                        onClick={handleNostrLoginLaunch}
                        disabled={!nostrLoginReady}
                        className="w-full btn-secondary text-sm py-3 disabled:opacity-50"
                      >
                        <span className="flex items-center justify-center gap-2">
                          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <circle cx="12" cy="12" r="10"/>
                            <path d="M8 12l2 2 4-4"/>
                          </svg>
                          {nostrLoginReady ? 'Nostrでログイン' : '読み込み中...'}
                        </span>
                      </button>
                    )}
                    <p className="text-center text-xs text-[var(--text-tertiary)]">
                      拡張機能 / Nostr Connect / 読み取り専用
                    </p>
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
            /* Passkey not supported - show nostr-login */
            <div className="animate-slideUp space-y-3">
              <div className="card p-6 text-center mb-4">
                <p className="text-sm text-[var(--text-secondary)] mb-2">
                  このブラウザはパスキーに対応していません
                </p>
                <p className="text-xs text-[var(--text-tertiary)]">
                  Chrome / Safari / Edge の最新版をお使いください
                </p>
              </div>
              
              {nostrLoginError ? (
                <div className="p-3 rounded-lg bg-red-50 dark:bg-red-900/20">
                  <p className="text-sm text-red-600 dark:text-red-400 text-center">
                    ログイン機能の読み込みに失敗しました。<br/>ページを再読み込みしてください。
                  </p>
                </div>
              ) : (
                <button
                  onClick={handleNostrLoginLaunch}
                  disabled={!nostrLoginReady}
                  className="w-full btn-line text-base py-4 disabled:opacity-50"
                >
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10"/>
                      <path d="M8 12l2 2 4-4"/>
                    </svg>
                    {nostrLoginReady ? 'ログイン' : '読み込み中...'}
                  </span>
                </button>
              )}
              <p className="text-center text-xs text-[var(--text-tertiary)]">
                拡張機能 / Nostr Connect / 読み取り専用
              </p>
              
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
        <a 
          href="https://github.com/nostr-jp" 
          target="_blank" 
          rel="noopener noreferrer"
          className="text-xs text-[var(--text-tertiary)] hover:text-[var(--line-green)] transition-colors"
        >
          Powered by Nostr
        </a>
      </div>
    </div>
  )
}
