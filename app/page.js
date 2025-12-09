'use client'

import { useState, useEffect, useRef } from 'react'
import LoginScreen from '@/components/LoginScreen'
import BottomNav from '@/components/BottomNav'
import HomeTab from '@/components/HomeTab'
import TalkTab from '@/components/TalkTab'
import TimelineTab from '@/components/TimelineTab'
import MiniAppTab from '@/components/MiniAppTab'
import { loadPubkey, clearPubkey } from '@/lib/nostr'

export default function Home() {
  const [pubkey, setPubkey] = useState(null)
  const [activeTab, setActiveTab] = useState('timeline')
  const [isLoading, setIsLoading] = useState(true)
  const timelineRef = useRef(null)

  useEffect(() => {
    // Check for stored pubkey on mount
    const storedPubkey = loadPubkey()
    if (storedPubkey) {
      setPubkey(storedPubkey)
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

  const handleTabChange = (tab) => {
    // If clicking timeline tab while already on timeline, refresh
    if (tab === 'timeline' && activeTab === 'timeline') {
      timelineRef.current?.refresh()
    }
    setActiveTab(tab)
  }

  // Loading state
  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[var(--bg-primary)]">
        <div className="text-center animate-fadeIn">
          <div className="w-24 h-24 mx-auto mb-4">
            <svg viewBox="0 0 512 512" className="w-full h-full">
              <defs>
                <linearGradient id="loadGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" style={{stopColor:'#06C755'}}/>
                  <stop offset="100%" style={{stopColor:'#00A67E'}}/>
                </linearGradient>
              </defs>
              <rect width="512" height="512" rx="128" fill="url(#loadGrad)"/>
              <path d="M256 100c-97.2 0-176 63.5-176 142 0 46.2 27.4 87.4 70 114.2l-14 56c-.8 3.2 3.2 5.6 5.8 3.5l62.8-47.1c16.6 4 34.2 6.1 52.4 6.1 97.2 0 176-63.5 176-142S353.2 100 256 100z" fill="white"/>
              <circle cx="190" cy="232" r="20" fill="#333"/>
              <circle cx="322" cy="232" r="20" fill="#333"/>
              <circle cx="198" cy="224" r="8" fill="#fff" className="animate-pulse"/>
              <circle cx="330" cy="224" r="8" fill="#fff" className="animate-pulse"/>
              <ellipse cx="140" cy="260" rx="22" ry="12" fill="#FFB6C1" opacity="0.5"/>
              <ellipse cx="372" cy="260" rx="22" ry="12" fill="#FFB6C1" opacity="0.5"/>
              <path d="M216 275 Q256 310 296 275" fill="none" stroke="#333" strokeWidth="6" strokeLinecap="round"/>
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
        return <TimelineTab ref={timelineRef} pubkey={pubkey} />
      case 'miniapp':
        return <MiniAppTab pubkey={pubkey} />
      default:
        return <TimelineTab ref={timelineRef} pubkey={pubkey} />
    }
  }

  return (
    <main className="min-h-screen bg-[var(--bg-primary)]">
      {renderTabContent()}
      <BottomNav activeTab={activeTab} onTabChange={handleTabChange} />
    </main>
  )
}
