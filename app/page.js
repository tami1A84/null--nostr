'use client'

import { useState, useEffect, useRef } from 'react'
import Image from 'next/image'
import LoginScreen from '@/components/LoginScreen'
import BottomNav from '@/components/BottomNav'
import HomeTab from '@/components/HomeTab'
import TalkTab from '@/components/TalkTab'
import TimelineTab from '@/components/TimelineTab'
import MiniAppTab from '@/components/MiniAppTab'
import { loadPubkey, clearPubkey, getLoginMethod } from '@/lib/nostr'

export default function Home() {
  const [pubkey, setPubkey] = useState(null)
  const [activeTab, setActiveTab] = useState('timeline')
  const [isLoading, setIsLoading] = useState(true)
  const [pendingDM, setPendingDM] = useState(null)
  const timelineRef = useRef(null)
  const talkRef = useRef(null)

  useEffect(() => {
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
                    window.nostrPrivateKey = privateKeyHex
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
        setPubkey(storedPubkey)
      }
      
      setIsLoading(false)
    }
    init()
  }, [])

  const handleLogin = (newPubkey) => {
    setPubkey(newPubkey)
  }

  const handleLogout = () => {
    clearPubkey()
    // Clear Nosskey data if it was used
    if (window.nosskeyManager) {
      window.nosskeyManager.clearStoredKeyInfo()
      window.nosskeyManager = undefined
    }
    window.nostrPrivateKey = undefined
    localStorage.removeItem('nurunuru_login_method')
    setPubkey(null)
    setActiveTab('timeline')
  }

  const handleTabChange = (tab) => {
    // If clicking timeline tab while already on timeline, refresh
    if (tab === 'timeline' && activeTab === 'timeline') {
      timelineRef.current?.refresh()
    }
    setActiveTab(tab)
  }

  // Start DM with a user - switch to talk tab and open conversation
  const handleStartDM = (targetPubkey) => {
    setPendingDM(targetPubkey)
    setActiveTab('talk')
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

  // Render active tab content
  const renderTabContent = () => {
    switch (activeTab) {
      case 'home':
        return <HomeTab pubkey={pubkey} onLogout={handleLogout} onStartDM={handleStartDM} />
      case 'talk':
        return <TalkTab ref={talkRef} pubkey={pubkey} pendingDM={pendingDM} onDMOpened={() => setPendingDM(null)} />
      case 'timeline':
        return <TimelineTab ref={timelineRef} pubkey={pubkey} onStartDM={handleStartDM} />
      case 'miniapp':
        return <MiniAppTab pubkey={pubkey} />
      default:
        return <TimelineTab ref={timelineRef} pubkey={pubkey} onStartDM={handleStartDM} />
    }
  }

  return (
    <main className="min-h-screen bg-[var(--bg-primary)]">
      {renderTabContent()}
      <BottomNav activeTab={activeTab} onTabChange={handleTabChange} />
    </main>
  )
}
