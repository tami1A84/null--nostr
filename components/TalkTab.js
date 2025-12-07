'use client'

import { useState, useEffect, useRef } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchEvents,
  parseProfile,
  shortenPubkey,
  formatTimestamp,
  sendEncryptedDM,
  decryptNip44,
  hasNip07,
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

  const loadConversations = async () => {
    if (!pubkey) return
    setLoading(true)

    try {
      // Fetch gift wrapped messages (kind 1059) - NIP-17
      const giftWraps = await fetchEvents(
        { kinds: [1059], '#p': [pubkey], limit: 100 },
        RELAYS
      )

      // Also fetch legacy DMs (kind 4) for compatibility
      const legacyReceived = await fetchEvents(
        { kinds: [4], '#p': [pubkey], limit: 50 },
        RELAYS
      )

      const legacySent = await fetchEvents(
        { kinds: [4], authors: [pubkey], limit: 50 },
        RELAYS
      )

      // Group by conversation partner
      const convMap = new Map()
      
      // Process legacy DMs
      for (const event of [...legacySent, ...legacyReceived]) {
        const partner = event.pubkey === pubkey
          ? event.tags.find(t => t[0] === 'p')?.[1]
          : event.pubkey

        if (partner && partner !== pubkey) {
          if (!convMap.has(partner) || convMap.get(partner).created_at < event.created_at) {
            convMap.set(partner, { lastEvent: event, timestamp: event.created_at })
          }
        }
      }

      // Process NIP-17 gift wraps
      for (const gw of giftWraps) {
        const pTag = gw.tags.find(t => t[0] === 'p')
        if (pTag) {
          // For gift wraps, we need to track by the wrapped sender
          // This requires decryption which we'll handle in chat view
          const partner = gw.pubkey // This is the random key, not actual sender
          // We'll update this properly when opening chats
        }
      }

      const convList = Array.from(convMap.entries()).map(([pk, data]) => ({
        pubkey: pk,
        lastEvent: data.lastEvent,
        timestamp: data.timestamp
      })).sort((a, b) => b.timestamp - a.timestamp)

      setConversations(convList)

      // Fetch profiles
      const pubkeys = convList.map(c => c.pubkey)
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

    try {
      // Fetch legacy DMs
      const received = await fetchEvents(
        { kinds: [4], authors: [partnerPubkey], '#p': [pubkey], limit: 50 },
        RELAYS
      )

      const sent = await fetchEvents(
        { kinds: [4], authors: [pubkey], '#p': [partnerPubkey], limit: 50 },
        RELAYS
      )

      const allMessages = [...received, ...sent]
        .sort((a, b) => a.created_at - b.created_at)
        .map(event => ({
          id: event.id,
          content: '🔒 暗号化されたメッセージ',
          encrypted: event.content,
          pubkey: event.pubkey,
          created_at: event.created_at,
          isSent: event.pubkey === pubkey
        }))

      // Try to decrypt messages
      if (hasNip07()) {
        for (const msg of allMessages) {
          try {
            const decrypted = await decryptNip44(
              msg.isSent ? partnerPubkey : msg.pubkey,
              msg.encrypted
            )
            msg.content = decrypted
          } catch (e) {
            // Keep encrypted placeholder
            console.log('Could not decrypt message:', e)
          }
        }
      }

      setMessages(allMessages)
    } catch (e) {
      console.error('Failed to load messages:', e)
    }
  }

  const handleSendMessage = async () => {
    if (!newMessage.trim() || !selectedChat || sending) return
    setSending(true)

    try {
      // Add optimistic message
      const optimisticMsg = {
        id: 'temp-' + Date.now(),
        content: newMessage,
        pubkey: pubkey,
        created_at: Math.floor(Date.now() / 1000),
        isSent: true,
        sending: true
      }
      setMessages(prev => [...prev, optimisticMsg])
      setNewMessage('')

      // Send using NIP-17
      await sendEncryptedDM(selectedChat, newMessage)
      
      // Update message as sent
      setMessages(prev => prev.map(m => 
        m.id === optimisticMsg.id ? { ...m, sending: false } : m
      ))
    } catch (e) {
      console.error('Failed to send message:', e)
      // Remove failed message
      setMessages(prev => prev.filter(m => !m.sending))
      alert('メッセージの送信に失敗しました: ' + e.message)
    } finally {
      setSending(false)
    }
  }

  const startNewChat = () => {
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

        {/* New Chat Modal */}
        {showNewChat && (
          <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center modal-overlay" onClick={() => setShowNewChat(false)}>
            <div 
              className="w-full sm:max-w-md bg-[var(--bg-primary)] rounded-t-2xl sm:rounded-2xl p-6 animate-slideUp"
              onClick={(e) => e.stopPropagation()}
            >
              <h3 className="text-lg font-bold text-[var(--text-primary)] mb-4">新しいトーク</h3>
              
              <div>
                <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">相手の公開鍵</label>
                <input
                  type="text"
                  value={newChatPubkey}
                  onChange={(e) => setNewChatPubkey(e.target.value)}
                  className="input-line font-mono text-sm"
                  placeholder="npub1... または hex"
                />
              </div>
              
              <div className="flex gap-3 mt-6">
                <button
                  onClick={() => setShowNewChat(false)}
                  className="flex-1 btn-secondary"
                >
                  キャンセル
                </button>
                <button
                  onClick={startNewChat}
                  className="flex-1 btn-line"
                >
                  開始
                </button>
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
                      🔒 暗号化されたメッセージ
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
