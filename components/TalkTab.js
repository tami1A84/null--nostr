'use client'

import { useState, useEffect, useRef } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchEvents,
  parseProfile,
  shortenPubkey,
  formatTimestamp,
  sendEncryptedDM,
  RELAYS
} from '@/lib/nostr'

export default function TalkTab({ pubkey }) {
  const [conversations, setConversations] = useState([])
  const [selectedChat, setSelectedChat] = useState(null)
  const [messages, setMessages] = useState([])
  const [newMessage, setNewMessage] = useState('')
  const [newChatPubkey, setNewChatPubkey] = useState('')
  const [showNewChat, setShowNewChat] = useState(false)
  const [loading, setLoading] = useState(true)
  const [profiles, setProfiles] = useState({})
  const [sending, setSending] = useState(false)
  const messagesEndRef = useRef(null)

  useEffect(() => {
    if (pubkey) {
      loadConversations()
    }
  }, [pubkey])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // Lock body scroll when modal is open
  useEffect(() => {
    if (showNewChat) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [showNewChat])

  const loadConversations = async () => {
    if (!pubkey) return
    setLoading(true)

    try {
      // Fetch NIP-17 gift wrapped messages (kind 1059) - limit for faster loading
      const giftWraps = await fetchEvents(
        { kinds: [1059], '#p': [pubkey], limit: 50 },
        RELAYS
      )

      if (giftWraps.length === 0) {
        setLoading(false)
        return
      }

      // Decrypt gift wraps in parallel batches for faster loading
      const convMap = new Map()
      const BATCH_SIZE = 5
      
      const decryptGiftWrap = async (gw) => {
        try {
          if (!window.nostr?.nip44?.decrypt) return null
          
          const sealJson = await window.nostr.nip44.decrypt(gw.pubkey, gw.content)
          const seal = JSON.parse(sealJson)
          
          if (seal.kind !== 13) return null
          
          const rumorJson = await window.nostr.nip44.decrypt(seal.pubkey, seal.content)
          const rumor = JSON.parse(rumorJson)
          
          if (rumor.kind !== 14) return null
          
          const partner = rumor.pubkey === pubkey
            ? rumor.tags.find(t => t[0] === 'p')?.[1]
            : rumor.pubkey
          
          if (!partner || partner === pubkey) return null
          
          return {
            partner,
            lastMessage: rumor.content,
            timestamp: rumor.created_at || gw.created_at
          }
        } catch (e) {
          return null
        }
      }
      
      // Process in batches
      for (let i = 0; i < giftWraps.length; i += BATCH_SIZE) {
        const batch = giftWraps.slice(i, i + BATCH_SIZE)
        const results = await Promise.allSettled(batch.map(decryptGiftWrap))
        
        for (const result of results) {
          if (result.status === 'fulfilled' && result.value) {
            const { partner, lastMessage, timestamp } = result.value
            if (!convMap.has(partner) || convMap.get(partner).timestamp < timestamp) {
              convMap.set(partner, { lastMessage, timestamp })
            }
          }
        }
        
        // Update UI progressively after each batch
        const convList = Array.from(convMap.entries()).map(([pk, data]) => ({
          pubkey: pk,
          lastMessage: data.lastMessage,
          timestamp: data.timestamp
        })).sort((a, b) => b.timestamp - a.timestamp)
        
        setConversations(convList)
      }

      // Fetch profiles
      const pubkeys = Array.from(convMap.keys())
      if (pubkeys.length > 0) {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: pubkeys },
          RELAYS
        )

        const profileMap = {}
        for (const event of profileEvents) {
          const p = parseProfile(event)
          if (p) profileMap[event.pubkey] = p
        }
        setProfiles(profileMap)
      }
    } catch (e) {
      console.error('Failed to load conversations:', e)
    } finally {
      setLoading(false)
    }
  }

  const openChat = async (partnerPubkey) => {
    setSelectedChat(partnerPubkey)
    setMessages([])

    // Load profile if not cached
    if (!profiles[partnerPubkey]) {
      try {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: [partnerPubkey], limit: 1 },
          RELAYS
        )
        if (profileEvents.length > 0) {
          const p = parseProfile(profileEvents[0])
          if (p) {
            setProfiles(prev => ({ ...prev, [partnerPubkey]: p }))
          }
        }
      } catch (e) {
        console.error('Failed to load profile:', e)
      }
    }

    try {
      // Fetch NIP-17 gift wraps addressed to me
      const giftWraps = await fetchEvents(
        { kinds: [1059], '#p': [pubkey], limit: 200 },
        RELAYS
      )

      const allMessages = []

      // Decrypt and filter messages for this conversation
      for (const gw of giftWraps) {
        try {
          if (window.nostr?.nip44?.decrypt) {
            // Decrypt gift wrap
            const sealJson = await window.nostr.nip44.decrypt(gw.pubkey, gw.content)
            const seal = JSON.parse(sealJson)
            
            if (seal.kind === 13) {
              // Decrypt seal to get rumor
              const rumorJson = await window.nostr.nip44.decrypt(seal.pubkey, seal.content)
              const rumor = JSON.parse(rumorJson)
              
              if (rumor.kind === 14) {
                // Check if this message is from/to the partner
                const messagePartner = rumor.pubkey === pubkey
                  ? rumor.tags.find(t => t[0] === 'p')?.[1]
                  : rumor.pubkey

                if (messagePartner === partnerPubkey) {
                  allMessages.push({
                    id: gw.id,
                    content: rumor.content,
                    pubkey: rumor.pubkey,
                    created_at: rumor.created_at || gw.created_at,
                    isSent: rumor.pubkey === pubkey
                  })
                }
              }
            }
          }
        } catch (e) {
          // Skip messages we can't decrypt
          console.log('Could not decrypt message')
        }
      }

      // Sort messages by time
      allMessages.sort((a, b) => a.created_at - b.created_at)
      setMessages(allMessages)
    } catch (e) {
      console.error('Failed to load messages:', e)
    }
  }

  const handleSendMessage = async () => {
    if (!newMessage.trim() || !selectedChat || sending) return
    setSending(true)

    const messageContent = newMessage.trim()
    const tempId = 'temp-' + Date.now()

    try {
      // Add optimistic message
      const optimisticMsg = {
        id: tempId,
        content: messageContent,
        pubkey: pubkey,
        created_at: Math.floor(Date.now() / 1000),
        isSent: true,
        sending: true
      }
      setMessages(prev => [...prev, optimisticMsg])
      setNewMessage('')

      // Send using NIP-17 (private direct messages)
      await sendEncryptedDM(selectedChat, messageContent)
      
      // Update message as sent
      setMessages(prev => prev.map(m => 
        m.id === tempId ? { ...m, sending: false } : m
      ))

      // Update conversation list - move to top or add new
      setConversations(prev => {
        const existing = prev.find(c => c.pubkey === selectedChat)
        const newTimestamp = Math.floor(Date.now() / 1000)
        
        if (existing) {
          // Move existing conversation to top
          return [
            { ...existing, timestamp: newTimestamp },
            ...prev.filter(c => c.pubkey !== selectedChat)
          ]
        } else {
          // Add new conversation
          return [
            { pubkey: selectedChat, timestamp: newTimestamp },
            ...prev
          ]
        }
      })
    } catch (e) {
      console.error('Failed to send message:', e)
      // Remove failed message
      setMessages(prev => prev.filter(m => m.id !== tempId))
      setNewMessage(messageContent)
      alert('メッセージの送信に失敗しました: ' + e.message)
    } finally {
      setSending(false)
    }
  }

  const startNewChat = async () => {
    if (!newChatPubkey.trim()) return

    try {
      let pk = newChatPubkey.trim()
      
      if (pk.startsWith('npub')) {
        const { data } = nip19.decode(pk)
        pk = data
      }

      if (pk.length !== 64) {
        alert('無効な公開鍵です')
        return
      }

      // Fetch profile for the new chat partner
      try {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: [pk], limit: 1 },
          RELAYS
        )
        if (profileEvents.length > 0) {
          const p = parseProfile(profileEvents[0])
          if (p) {
            setProfiles(prev => ({ ...prev, [pk]: p }))
          }
        }
      } catch (e) {
        console.error('Failed to load profile:', e)
      }

      // Add to conversations if not exists
      if (!conversations.find(c => c.pubkey === pk)) {
        setConversations(prev => [{
          pubkey: pk,
          timestamp: Math.floor(Date.now() / 1000)
        }, ...prev])
      }

      setShowNewChat(false)
      setNewChatPubkey('')
      openChat(pk)
    } catch (e) {
      alert('無効な公開鍵形式です')
    }
  }

  // Chat list view
  if (!selectedChat) {
    return (
      <div className="min-h-screen pb-16">
        {/* Header */}
        <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
          <div className="flex items-center justify-between px-4 h-12">
            <h1 className="text-lg font-semibold text-[var(--text-primary)]">トーク</h1>
            <button
              onClick={() => setShowNewChat(true)}
              className="w-8 h-8 rounded-full bg-[var(--line-green)] flex items-center justify-center action-btn"
            >
              <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
            </button>
          </div>
        </header>

        {/* New Chat Modal - Fixed layout */}
        {showNewChat && (
          <div className="fixed inset-0 z-50 modal-overlay" onClick={() => setShowNewChat(false)}>
            <div className="min-h-screen flex items-center justify-center p-4">
              <div 
                className="w-full max-w-md bg-[var(--bg-primary)] rounded-2xl shadow-xl animate-scaleIn"
                onClick={(e) => e.stopPropagation()}
              >
                {/* Modal Header */}
                <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)]">
                  <h3 className="text-lg font-bold text-[var(--text-primary)]">新しいトーク</h3>
                  <button onClick={() => setShowNewChat(false)} className="text-[var(--text-tertiary)] action-btn p-1">
                    <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <line x1="18" y1="6" x2="6" y2="18"/>
                      <line x1="6" y1="6" x2="18" y2="18"/>
                    </svg>
                  </button>
                </div>
                
                {/* Modal Body */}
                <div className="p-4">
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">相手の公開鍵</label>
                  <input
                    type="text"
                    value={newChatPubkey}
                    onChange={(e) => setNewChatPubkey(e.target.value)}
                    className="input-line font-mono text-sm"
                    placeholder="npub1... または hex"
                    autoFocus
                  />
                </div>
                
                {/* Modal Footer - Always visible */}
                <div className="flex gap-3 p-4 border-t border-[var(--border-color)]">
                  <button
                    onClick={() => setShowNewChat(false)}
                    className="flex-1 btn-secondary"
                  >
                    キャンセル
                  </button>
                  <button
                    onClick={startNewChat}
                    disabled={!newChatPubkey.trim()}
                    className="flex-1 btn-line disabled:opacity-50"
                  >
                    開始
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Conversation List */}
        {loading ? (
          <div className="px-4 py-4 space-y-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="flex items-center gap-3 p-3">
                <div className="w-12 h-12 rounded-full skeleton" />
                <div className="flex-1">
                  <div className="skeleton h-4 w-24 rounded mb-2" />
                  <div className="skeleton h-3 w-40 rounded" />
                </div>
              </div>
            ))}
          </div>
        ) : conversations.length === 0 ? (
          <div className="px-4 py-16 text-center">
            <div className="w-20 h-20 mx-auto mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
              <svg className="w-10 h-10 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.42-4.03 8-9 8-1.5 0-2.92-.32-4.19-.88L3 21l1.9-3.8C3.71 15.77 3 14.01 3 12c0-4.42 4.03-8 9-8s9 3.58 9 8z"/>
              </svg>
            </div>
            <p className="text-[var(--text-secondary)] mb-1">トークがありません</p>
            <p className="text-sm text-[var(--text-tertiary)]">右上の＋から始めましょう</p>
          </div>
        ) : (
          <div className="divide-y divide-[var(--border-color)]">
            {conversations.map((conv, index) => {
              const profile = profiles[conv.pubkey]
              return (
                <button
                  key={conv.pubkey}
                  onClick={() => openChat(conv.pubkey)}
                  className="w-full flex items-center gap-3 px-4 py-3 list-item animate-fadeIn"
                  style={{ animationDelay: `${index * 30}ms` }}
                >
                  <div className="w-12 h-12 rounded-full overflow-hidden bg-[var(--bg-tertiary)] flex-shrink-0">
                    {profile?.picture ? (
                      <img src={profile.picture} alt="" className="w-full h-full object-cover" />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center">
                        <svg className="w-6 h-6 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                          <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                        </svg>
                      </div>
                    )}
                  </div>
                  <div className="flex-1 min-w-0 text-left">
                    <div className="flex items-center justify-between">
                      <span className="font-medium text-[var(--text-primary)] truncate">
                        {profile?.name || shortenPubkey(conv.pubkey, 8)}
                      </span>
                      <span className="text-xs text-[var(--text-tertiary)] flex-shrink-0 ml-2">
                        {formatTimestamp(conv.timestamp)}
                      </span>
                    </div>
                    <p className="text-sm text-[var(--text-secondary)] truncate mt-0.5">
                      {conv.lastMessage ? conv.lastMessage : 'メッセージなし'}
                    </p>
                  </div>
                </button>
              )
            })}
          </div>
        )}
      </div>
    )
  }

  // Chat view
  const chatProfile = profiles[selectedChat]

  return (
    <div className="min-h-screen pb-16 flex flex-col">
      {/* Chat Header */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center gap-3 px-2 h-12">
          <button
            onClick={() => setSelectedChat(null)}
            className="w-10 h-10 flex items-center justify-center action-btn"
          >
            <svg className="w-6 h-6 text-[var(--text-primary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="15 18 9 12 15 6"/>
            </svg>
          </button>
          <div className="w-9 h-9 rounded-full overflow-hidden bg-[var(--bg-tertiary)] flex-shrink-0">
            {chatProfile?.picture ? (
              <img src={chatProfile.picture} alt="" className="w-full h-full object-cover" />
            ) : (
              <div className="w-full h-full flex items-center justify-center">
                <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                </svg>
              </div>
            )}
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="font-semibold text-[var(--text-primary)] truncate text-sm">
              {chatProfile?.name || shortenPubkey(selectedChat, 8)}
            </h2>
          </div>
        </div>
      </header>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-4 space-y-2 bg-[var(--bg-secondary)]">
        {messages.length === 0 && (
          <div className="text-center py-8">
            <p className="text-[var(--text-tertiary)] text-sm">メッセージを送信してみましょう</p>
          </div>
        )}
        {messages.map((msg, index) => (
          <div
            key={msg.id}
            className={`flex animate-fadeIn ${msg.isSent ? 'justify-end' : 'justify-start'}`}
            style={{ animationDelay: `${index * 20}ms` }}
          >
            <div className={msg.isSent ? 'message-sent' : 'message-received'}>
              <p className="text-sm whitespace-pre-wrap break-words">{msg.content}</p>
              <p className={`text-xs mt-1 ${msg.isSent ? 'text-green-100' : 'text-[var(--text-tertiary)]'}`}>
                {msg.sending ? '送信中...' : formatTimestamp(msg.created_at)}
              </p>
            </div>
          </div>
        ))}
        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div className="sticky bottom-14 left-0 right-0 bg-[var(--bg-primary)] border-t border-[var(--border-color)] p-2">
        <div className="flex items-center gap-2">
          <input
            type="text"
            value={newMessage}
            onChange={(e) => setNewMessage(e.target.value)}
            onKeyPress={(e) => e.key === 'Enter' && !e.shiftKey && handleSendMessage()}
            className="flex-1 input-line py-2.5"
            placeholder="メッセージ"
          />
          <button
            onClick={handleSendMessage}
            disabled={!newMessage.trim() || sending}
            className="w-10 h-10 rounded-full bg-[var(--line-green)] flex items-center justify-center disabled:opacity-50 action-btn"
          >
            <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="currentColor">
              <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
            </svg>
          </button>
        </div>
      </div>
    </div>
  )
}
