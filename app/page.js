'use client'

import { useState, useEffect } from 'react'
import LoginScreen from '@/components/LoginScreen'
import BottomNav from '@/components/BottomNav'
import HomeTab from '@/components/HomeTab'
import TalkTab from '@/components/TalkTab'
import TimelineTab from '@/components/TimelineTab'
import WalletTab from '@/components/WalletTab'
import { loadPubkey, clearPubkey, loadNWC, hasNip07 } from '@/lib/nostr'

export default function Home() {
  const [pubkey, setPubkey] = useState(null)
  const [activeTab, setActiveTab] = useState('timeline')
  const [nwcUrl, setNwcUrl] = useState(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    // Check for stored pubkey on mount
    const storedPubkey = loadPubkey()
    if (storedPubkey) {
      setPubkey(storedPubkey)
    }
    
    // Check for stored NWC
    const storedNWC = loadNWC()
    if (storedNWC) {
      setNwcUrl(storedNWC)
    }
    
    setIsLoading(false)
  }, [])

  const handleLogin = (newPubkey) => {
    setPubkey(newPubkey)
  }

  const handleLogout = () => {
    clearPubkey()
    setPubkey(null)
    setActiveTab('timeline')
  }

  const handleNWCChange = (url) => {
    setNwcUrl(url)
  }

  const handleZapRequest = () => {
    setActiveTab('wallet')
  }

  // Loading state
  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[var(--bg-primary)]">
        <div className="text-center animate-fadeIn">
          <div className="w-20 h-20 mx-auto mb-4">
            <svg viewBox="0 0 512 512" className="w-full h-full">
              <defs>
                <linearGradient id="loadGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" style={{stopColor:'#06C755'}}/>
                  <stop offset="100%" style={{stopColor:'#04A347'}}/>
                </linearGradient>
              </defs>
              <rect width="512" height="512" rx="128" fill="url(#loadGrad)"/>
              <g fill="white">
                <path d="M256 100c-97.2 0-176 63.5-176 142 0 46.2 27.4 87.4 70 114.2l-14 56c-.8 3.2 3.2 5.6 5.8 3.5l62.8-47.1c16.6 4 34.2 6.1 52.4 6.1 97.2 0 176-63.5 176-142S353.2 100 256 100z"/>
                <circle cx="176" cy="242" r="24" fill="#06C755" className="animate-pulse"/>
                <circle cx="256" cy="242" r="24" fill="#06C755" className="animate-pulse stagger-1"/>
                <circle cx="336" cy="242" r="24" fill="#06C755" className="animate-pulse stagger-2"/>
              </g>
            </svg>
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
        return <HomeTab pubkey={pubkey} onLogout={handleLogout} />
      case 'talk':
        return <TalkTab pubkey={pubkey} />
      case 'timeline':
        return <TimelineTab pubkey={pubkey} nwcUrl={nwcUrl} onZap={handleZapRequest} />
      case 'wallet':
        return <WalletTab nwcUrl={nwcUrl} onNWCChange={handleNWCChange} />
      default:
        return <TimelineTab pubkey={pubkey} nwcUrl={nwcUrl} onZap={handleZapRequest} />
    }
  }

  return (
    <main className="min-h-screen bg-[var(--bg-primary)]">
      {renderTabContent()}
      <BottomNav activeTab={activeTab} onTabChange={setActiveTab} />
    </main>
  )
}
