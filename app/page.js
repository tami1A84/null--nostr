'use client'

import { useState, useEffect, useRef } from 'react'
import Image from 'next/image'
import LoginScreen from '@/components/LoginScreen'
import BottomNav from '@/components/BottomNav'
import HomeTab from '@/components/HomeTab'
import TalkTab from '@/components/TalkTab'
import TimelineTab from '@/components/TimelineTab'
import MiniAppTab from '@/components/MiniAppTab'
import { loadPubkey, clearPubkey, getLoginMethod, startBackgroundPrefetch, clearPrefetchPromises, setStoredPrivateKey, clearStoredPrivateKey } from '@/lib/nostr'
import { initCache } from '@/lib/cache'

// Desktop sidebar navigation items
const navItems = [
  {
    id: 'home',
    label: 'ホーム',
    icon: (active) => (
      <svg className="w-7 h-7" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={active ? 0 : 1.8}>
        {active ? (
          <path d="M12 2L3 9v12a1 1 0 001 1h5a1 1 0 001-1v-5a1 1 0 011-1h2a1 1 0 011 1v5a1 1 0 001 1h5a1 1 0 001-1V9l-9-7z"/>
        ) : (
          <path strokeLinecap="round" strokeLinejoin="round" d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2V9z M9 22V12h6v10"/>
        )}
      </svg>
    )
  },
  {
    id: 'talk',
    label: 'トーク',
    icon: (active) => (
      <svg className="w-7 h-7" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={active ? 0 : 1.8}>
        {active ? (
          <path d="M12 2C6.48 2 2 5.58 2 10c0 2.62 1.34 4.98 3.5 6.56V21l4.22-2.33c.73.18 1.49.33 2.28.33 5.52 0 10-3.58 10-8S17.52 2 12 2z"/>
        ) : (
          <path strokeLinecap="round" strokeLinejoin="round" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.42-4.03 8-9 8-1.5 0-2.92-.32-4.19-.88L3 21l1.9-3.8C3.71 15.77 3 14.01 3 12c0-4.42 4.03-8 9-8s9 3.58 9 8z"/>
        )}
      </svg>
    )
  },
  {
    id: 'timeline',
    label: 'タイムライン',
    icon: (active) => (
      <svg className="w-7 h-7" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={active ? 0 : 1.8}>
        {active ? (
          <path d="M4 4h16a2 2 0 012 2v12a2 2 0 01-2 2H4a2 2 0 01-2-2V6a2 2 0 012-2zm2 4v2h4V8H6zm0 4v2h8v-2H6zm0 4v2h6v-2H6zm10-8v10h2V8h-2z"/>
        ) : (
          <path strokeLinecap="round" strokeLinejoin="round" d="M19 20H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v12a2 2 0 01-2 2zM16 2v4M8 2v4M3 10h18"/>
        )}
      </svg>
    )
  },
  {
    id: 'miniapp',
    label: 'ミニアプリ',
    icon: (active) => (
      <svg className="w-7 h-7" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={active ? 0 : 1.8}>
        {active ? (
          <path d="M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm10 0h6v6h-6v-6z"/>
        ) : (
          <path strokeLinecap="round" strokeLinejoin="round" d="M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm10 0h6v6h-6v-6z"/>
        )}
      </svg>
    )
  }
]

export default function Home() {
  const [pubkey, setPubkey] = useState(null)
  const [activeTab, setActiveTab] = useState('timeline')
  const [isLoading, setIsLoading] = useState(true)
  const [pendingDM, setPendingDM] = useState(null)
  const [tabsReady, setTabsReady] = useState({ home: false, talk: false })
  const [isDesktop, setIsDesktop] = useState(false)
  const timelineRef = useRef(null)
  const talkRef = useRef(null)
  const homeRef = useRef(null)
  // Scroll container refs for each tab
  const timelineContainerRef = useRef(null)
  const homeContainerRef = useRef(null)
  const talkContainerRef = useRef(null)
  const miniappContainerRef = useRef(null)

  // Detect desktop/mobile
  useEffect(() => {
    const checkDesktop = () => setIsDesktop(window.innerWidth >= 1024)
    checkDesktop()
    window.addEventListener('resize', checkDesktop)
    return () => window.removeEventListener('resize', checkDesktop)
  }, [])

  useEffect(() => {
    // Initialize cache (clear expired entries)
    initCache()
    
    // Register Service Worker for PWA
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js')
        .catch((error) => {
          console.error('SW registration failed:', error)
        })
    }
    
    const init = async () => {
      // Check for stored pubkey on mount
      const storedPubkey = loadPubkey()
      if (storedPubkey) {
        // Restore Nosskey manager if logged in via Nosskey
        const loginMethod = getLoginMethod()
        if (loginMethod === 'nosskey') {
          try {
            const { NosskeyManager } = await import('nosskey-sdk')
            const manager = new NosskeyManager({
              storageOptions: { enabled: true, storageKey: 'nurunuru_nosskey' },
              cacheOptions: { enabled: true, timeoutMs: 3600000 }
            })
            
            if (manager.hasKeyInfo()) {
              window.nosskeyManager = manager
              // Try to restore private key for auto-signing
              try {
                const keyInfo = manager.getCurrentKeyInfo()
                if (keyInfo) {
                  const privateKeyHex = await manager.exportNostrKey(keyInfo)
                  if (privateKeyHex) {
                    setStoredPrivateKey(storedPubkey, privateKeyHex)
                  }
                }
              } catch (e) {
                console.log('Could not restore private key:', e)
              }
            }
          } catch (e) {
            console.log('Failed to restore Nosskey:', e)
          }
        }
        
        // Start background prefetch immediately
        startBackgroundPrefetch(storedPubkey)
        
        setPubkey(storedPubkey)
      }
      
      setIsLoading(false)
    }
    init()
  }, [])

  // Start background prefetch when pubkey changes
  useEffect(() => {
    if (pubkey) {
      startBackgroundPrefetch(pubkey)
      
      // Mark tabs as ready after a short delay (let timeline load first)
      const timer = setTimeout(() => {
        setTabsReady({ home: true, talk: true })
      }, 500)
      
      return () => clearTimeout(timer)
    }
  }, [pubkey])

  const handleLogin = (newPubkey) => {
    startBackgroundPrefetch(newPubkey)
    setPubkey(newPubkey)
  }

  const handleLogout = () => {
    clearPubkey()
    clearPrefetchPromises()
    clearStoredPrivateKey()
    // Clear Nosskey data if it was used
    if (window.nosskeyManager) {
      window.nosskeyManager.clearStoredKeyInfo()
      window.nosskeyManager = undefined
    }
    localStorage.removeItem('nurunuru_login_method')
    localStorage.removeItem('nurunuru_bunker_session')

    // Trigger nostr-login logout
    document.dispatchEvent(new Event('nlLogout'))

    setPubkey(null)
    setActiveTab('timeline')
    setTabsReady({ home: false, talk: false })
  }

  const handleTabChange = (tab) => {
    // Close search modal in timeline when switching tabs
    timelineRef.current?.closeSearch?.()
    
    // Always close profile in any tab when nav is clicked
    timelineRef.current?.closeProfile?.()
    homeRef.current?.closeProfile?.()
    
    // If clicking the same tab, refresh it
    if (tab === activeTab) {
      switch (tab) {
        case 'timeline':
          timelineRef.current?.refresh()
          timelineContainerRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
          break
        case 'home':
          homeRef.current?.refresh?.()
          homeContainerRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
          break
        case 'talk':
          talkRef.current?.refresh?.()
          talkContainerRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
          break
        case 'miniapp':
          miniappContainerRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
          break
      }
      return
    }
    setActiveTab(tab)
  }

  // Start DM with a user - switch to talk tab and open conversation
  const handleStartDM = (targetPubkey) => {
    setPendingDM(targetPubkey)
    setActiveTab('talk')
  }

  // Handle hashtag click from HomeTab - switch to timeline and search
  const handleHashtagSearch = (hashtag) => {
    setActiveTab('timeline')
    // TimelineTab will handle opening search with the hashtag
    // We need to add a method to timelineRef or use a different approach
    setTimeout(() => {
      if (timelineRef.current?.openSearch) {
        timelineRef.current.openSearch(`#${hashtag}`)
      }
    }, 100)
  }

  // Loading state with mascot image
  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[var(--bg-primary)]">
        <div className="text-center animate-fadeIn">
          <div className="w-28 h-28 mx-auto mb-4 relative">
            <Image
              src="/nurunuru-star.png"
              alt="ぬるぬるのすたー"
              width={112}
              height={112}
              className="rounded-2xl animate-pulse"
              priority
            />
          </div>
          <p className="text-[var(--text-tertiary)] text-sm">読み込み中...</p>
        </div>
      </div>
    )
  }

  // Show login screen if not logged in
  if (!pubkey) {
    return <LoginScreen onLogin={handleLogin} />
  }

  // Render layout with desktop sidebar
  return (
    <main className="min-h-screen bg-[var(--bg-primary)]">
      {/* Desktop Sidebar Navigation */}
      <aside className="desktop-sidebar">
        {/* Logo */}
        <div className="flex items-center gap-3 px-3 py-4 mb-4">
          <Image
            src="/favicon-512.png"
            alt="ぬるぬる"
            width={40}
            height={40}
            className="rounded-xl"
          />
          <span className="text-xl font-bold text-[var(--text-primary)]">ぬるぬる</span>
        </div>
        
        {/* Nav Items */}
        <nav className="flex-1 space-y-1">
          {navItems.map((item) => {
            const isActive = activeTab === item.id
            return (
              <button
                key={item.id}
                onClick={() => handleTabChange(item.id)}
                className={`sidebar-nav-item w-full ${isActive ? 'active' : ''}`}
              >
                {item.icon(isActive)}
                <span>{item.label}</span>
              </button>
            )
          })}
        </nav>
        
        {/* Compose Button */}
        <button
          onClick={() => {
            setActiveTab('timeline')
            // Trigger post modal via exposed ref
            setTimeout(() => timelineRef.current?.openPostModal?.(), 100)
          }}
          className="compose-btn-desktop mt-4"
        >
          <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          <span>投稿する</span>
        </button>
      </aside>

      {/* Main Content Area */}
      <div className="desktop-main">
        {/* Timeline - always render first as default tab */}
        <div 
          ref={timelineContainerRef}
          className={`fixed inset-0 lg:left-[240px] xl:left-[280px] bottom-16 lg:bottom-0 overflow-y-auto ${activeTab === 'timeline' ? '' : 'invisible pointer-events-none'}`}
          style={{ zIndex: activeTab === 'timeline' ? 1 : 0 }}
        >
          <TimelineTab 
            ref={timelineRef} 
            pubkey={pubkey} 
            onStartDM={handleStartDM} 
            scrollContainerRef={timelineContainerRef}
            onPostPublished={() => homeRef.current?.refresh?.()}
            isDesktop={isDesktop}
            isActive={activeTab === 'timeline'}
          />
        </div>
        
        {/* Home - render when ready or when active */}
        {(tabsReady.home || activeTab === 'home') && (
          <div 
            ref={homeContainerRef}
            className={`fixed inset-0 lg:left-[240px] xl:left-[280px] bottom-16 lg:bottom-0 overflow-y-auto ${activeTab === 'home' ? '' : 'invisible pointer-events-none'}`}
            style={{ zIndex: activeTab === 'home' ? 1 : 0 }}
          >
            <HomeTab ref={homeRef} pubkey={pubkey} onLogout={handleLogout} onStartDM={handleStartDM} onHashtagClick={handleHashtagSearch} />
          </div>
        )}
        
        {/* Talk - render when ready or when active */}
        {(tabsReady.talk || activeTab === 'talk') && (
          <div 
            ref={talkContainerRef}
            className={`fixed inset-0 lg:left-[240px] xl:left-[280px] bottom-16 lg:bottom-0 overflow-y-auto ${activeTab === 'talk' ? '' : 'invisible pointer-events-none'}`}
            style={{ zIndex: activeTab === 'talk' ? 1 : 0 }}
          >
            <TalkTab ref={talkRef} pubkey={pubkey} pendingDM={pendingDM} onDMOpened={() => setPendingDM(null)} />
          </div>
        )}
        
        {/* MiniApp - only render when active (settings don't need prefetch) */}
        {activeTab === 'miniapp' && (
          <div
            ref={miniappContainerRef}
            className="fixed inset-0 lg:left-[240px] xl:left-[280px] bottom-16 lg:bottom-0 overflow-y-auto"
            style={{ zIndex: 1 }}
          >
            <MiniAppTab pubkey={pubkey} onLogout={handleLogout} />
          </div>
        )}
      </div>
      
      {/* Mobile Bottom Navigation */}
      <div className="mobile-bottom-nav">
        <BottomNav activeTab={activeTab} onTabChange={handleTabChange} />
      </div>
    </main>
  )
}
