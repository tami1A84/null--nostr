'use client'

import { useState, useEffect, useRef, forwardRef, useImperativeHandle } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchEvents,
  parseProfile,
  shortenPubkey,
  formatTimestamp,
  sendEncryptedDM,
  hasStoredPrivateKey,
  decryptNip44,
  fetchProfilesBatch,
  getAllCachedProfiles,
  RELAYS
} from '@/lib/nostr'
import { uploadImagesInParallel } from '@/lib/imageUtils'
import EmojiPicker from './EmojiPicker'
import URLPreview from './URLPreview'

// Render content preview with custom emojis (for input preview)
function MessagePreview({ content, customEmojis = [] }) {
  if (!content) return null

  // Build emoji map from selected emojis
  const emojiMap = {}
  customEmojis.forEach(emoji => {
    if (emoji.shortcode && emoji.url) {
      emojiMap[emoji.shortcode] = emoji.url
    }
  })

  // Split by custom emoji shortcodes
  const emojiRegex = /(:[a-zA-Z0-9_]+:)/g
  const parts = content.split(emojiRegex).filter(Boolean)

  return (
    <div className="text-sm text-[var(--text-primary)] whitespace-pre-wrap break-words">
      {parts.map((part, i) => {
        const emojiMatch = part.match(/^:([a-zA-Z0-9_]+):$/)
        if (emojiMatch) {
          const shortcode = emojiMatch[1]
          const emojiUrl = emojiMap[shortcode]
          if (emojiUrl) {
            return (
              <img
                key={i}
                src={emojiUrl}
                alt={`:${shortcode}:`}
                title={`:${shortcode}:`}
                className="inline-block w-5 h-5 align-middle mx-0.5"
                onError={(e) => {
                  e.target.style.display = 'none'
                }}
              />
            )
          }
          return <span key={i} className="text-[var(--text-tertiary)]">{part}</span>
        }
        return <span key={i}>{part}</span>
      })}
    </div>
  )
}

// Image URL detection regex
const IMAGE_URL_REGEX = /(https?:\/\/[^\s]+\.(?:jpg|jpeg|png|gif|webp|svg)(?:\?[^\s]*)?)/gi

// Render message content with CW, custom emojis, and images
function MessageContent({ content, isSent }) {
  const [cwRevealed, setCwRevealed] = useState(false)

  if (!content) return null

  // Check for CW pattern: [CW: reason]\n\ncontent
  const cwMatch = content.match(/^\[CW:\s*([^\]]*)\]\s*\n\n([\s\S]*)$/)

  if (cwMatch) {
    const cwReason = cwMatch[1] || '警告'
    const actualContent = cwMatch[2]

    return (
      <div>
        <button
          onClick={() => setCwRevealed(!cwRevealed)}
          className={`flex items-center gap-1 text-xs font-medium mb-1 ${isSent ? 'text-green-100' : 'text-orange-500'}`}
        >
          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/>
            <line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
          CW: {cwReason}
          <svg className={`w-3 h-3 transition-transform ${cwRevealed ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </button>
        {cwRevealed && (
          <div className="mt-1 pt-1 border-t border-current/20">
            <RenderMessageText content={actualContent} isSent={isSent} />
          </div>
        )}
      </div>
    )
  }

  return <RenderMessageText content={content} isSent={isSent} />
}

// Render text with custom emojis, images, and URL previews
function RenderMessageText({ content, isSent }) {
  if (!content) return null

  // Split by URLs (both image and general) and emoji shortcodes
  const combinedRegex = /(https?:\/\/[^\s]+|:[a-zA-Z0-9_]+:)/gi
  const parts = content.split(combinedRegex).filter(Boolean)

  // Track URLs for preview (avoid duplicates)
  const previewUrls = []

  const renderedParts = parts.map((part, i) => {
    // Check for image URL
    if (part.match(/^https?:\/\/[^\s]+\.(?:jpg|jpeg|png|gif|webp|svg)(?:\?[^\s]*)?$/i)) {
      return (
        <div key={i} className="my-1">
          <img
            src={part}
            alt=""
            className="max-w-full max-h-48 rounded-lg object-contain"
            loading="lazy"
            onError={(e) => {
              // If image fails to load, show as link
              e.target.outerHTML = `<a href="${part}" target="_blank" rel="noopener noreferrer" class="underline break-all">${part}</a>`
            }}
          />
        </div>
      )
    }

    // Check for video URL
    if (part.match(/^https?:\/\/[^\s]+\.(?:mp4|webm|mov)(?:\?[^\s]*)?$/i)) {
      return (
        <div key={i} className="my-1">
          <video
            src={part}
            controls
            className="max-w-full max-h-48 rounded-lg"
          />
        </div>
      )
    }

    // Check for general URL (for preview)
    if (part.match(/^https?:\/\//)) {
      // Add to preview list if not already there
      if (!previewUrls.includes(part)) {
        previewUrls.push(part)
      }
      return (
        <a
          key={i}
          href={part}
          target="_blank"
          rel="noopener noreferrer"
          className="text-[var(--line-green)] hover:underline break-all"
        >
          {part.length > 50 ? part.slice(0, 50) + '...' : part}
        </a>
      )
    }

    // Check for custom emoji shortcode
    const emojiMatch = part.match(/^:([a-zA-Z0-9_]+):$/)
    if (emojiMatch) {
      const shortcode = emojiMatch[1]
      // For now, we don't have the emoji URL map for received messages
      // Show as styled shortcode
      return (
        <span key={i} className={`${isSent ? 'text-green-100/70' : 'text-[var(--text-tertiary)]'}`}>
          {part}
        </span>
      )
    }

    return <span key={i}>{part}</span>
  })

  return (
    <div className="text-sm whitespace-pre-wrap break-words">
      {renderedParts}
      {/* Show URL previews for non-media URLs (max 2) */}
      {previewUrls.slice(0, 2).map((url, i) => (
        <URLPreview key={`preview-${i}`} url={url} compact />
      ))}
    </div>
  )
}

const TalkTab = forwardRef(function TalkTab({ pubkey, pendingDM, onDMOpened }, ref) {
  const [conversations, setConversations] = useState([])
  const [selectedChat, setSelectedChat] = useState(null)
  const [messages, setMessages] = useState([])
  const [newMessage, setNewMessage] = useState('')
  const [newChatPubkey, setNewChatPubkey] = useState('')
  const [showNewChat, setShowNewChat] = useState(false)
  const [loading, setLoading] = useState(true)
  const [profiles, setProfiles] = useState({})
  const [sending, setSending] = useState(false)
  // New state for enhanced message input
  const [imageFiles, setImageFiles] = useState([])
  const [imagePreviews, setImagePreviews] = useState([])
  const [uploadingImage, setUploadingImage] = useState(false)
  const [showEmojiPicker, setShowEmojiPicker] = useState(false)
  const [selectedEmojis, setSelectedEmojis] = useState([])
  const [contentWarning, setContentWarning] = useState('')
  const [showCWInput, setShowCWInput] = useState(false)
  const messagesEndRef = useRef(null)
  const fileInputRef = useRef(null)
  const textareaRef = useRef(null)
  
  // DM support state - check dynamically
  const [hasDMSupport, setHasDMSupport] = useState(true) // Default to true, will update
  
  // Check DM support on mount and periodically
  useEffect(() => {
    const checkDMSupport = () => {
      const hasPrivKey = hasStoredPrivateKey()
      const hasNip44 = typeof window !== 'undefined' && window.nostr?.nip44?.decrypt
      const hasManager = typeof window !== 'undefined' && window.nosskeyManager !== undefined
      // Support DM if we have private key, NIP-44 extension, or Nosskey manager
      setHasDMSupport(hasPrivKey || !!hasNip44 || hasManager)
    }
    
    // Initial check
    checkDMSupport()
    
    // Recheck multiple times as things load asynchronously
    const timers = [
      setTimeout(checkDMSupport, 100),
      setTimeout(checkDMSupport, 500),
      setTimeout(checkDMSupport, 1000),
    ]
    
    return () => timers.forEach(t => clearTimeout(t))
  }, [pubkey])

  useEffect(() => {
    if (pubkey) {
      // Load cached profiles immediately for instant display
      const cachedProfiles = getAllCachedProfiles()
      if (Object.keys(cachedProfiles).length > 0) {
        setProfiles(cachedProfiles)
      }
      loadConversations()
    }
  }, [pubkey])

  // Forward ref for useImperativeHandle - will be set after openChat is defined
  const startNewChatRef = useRef(null)

  // Handle pending DM from other tabs
  useEffect(() => {
    if (pendingDM && pubkey) {
      // Use timeout to ensure startNewChatRef is populated
      const tryStartChat = () => {
        if (startNewChatRef.current) {
          startNewChatRef.current(pendingDM)
          if (onDMOpened) onDMOpened()
        } else {
          // Retry after a short delay if ref not ready
          setTimeout(tryStartChat, 100)
        }
      }
      tryStartChat()
    }
  }, [pendingDM, pubkey])

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
          // Use our decryptNip44 which supports both private key and NIP-07
          const sealJson = await decryptNip44(gw.pubkey, gw.content)
          const seal = JSON.parse(sealJson)
          
          if (seal.kind !== 13) return null
          
          const rumorJson = await decryptNip44(seal.pubkey, seal.content)
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
        
        // Update UI progressively after each batch - merge with existing
        const convList = Array.from(convMap.entries()).map(([pk, data]) => ({
          pubkey: pk,
          lastMessage: data.lastMessage,
          timestamp: data.timestamp
        }))
        
        setConversations(prev => {
          // Merge: keep manually added conversations, update existing ones
          const merged = new Map()
          
          // Add loaded conversations
          convList.forEach(c => merged.set(c.pubkey, c))
          
          // Keep manually added conversations that aren't in loaded list
          prev.forEach(c => {
            if (!merged.has(c.pubkey)) {
              merged.set(c.pubkey, c)
            }
          })
          
          // Sort by timestamp
          return Array.from(merged.values()).sort((a, b) => b.timestamp - a.timestamp)
        })
      }

      // Fetch profiles using batch with caching
      const pubkeys = Array.from(convMap.keys())
      if (pubkeys.length > 0) {
        const profileMap = await fetchProfilesBatch(pubkeys)
        setProfiles(prev => ({ ...prev, ...profileMap }))
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
          // Decrypt gift wrap using our function
          const sealJson = await decryptNip44(gw.pubkey, gw.content)
          const seal = JSON.parse(sealJson)
          
          if (seal.kind === 13) {
            // Decrypt seal to get rumor
            const rumorJson = await decryptNip44(seal.pubkey, seal.content)
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

  // Start a new chat with a user (called from external components)
  const startNewChat = async (targetPubkey) => {
    // Fetch profile first if needed
    if (!profiles[targetPubkey]) {
      try {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: [targetPubkey], limit: 1 },
          RELAYS
        )
        if (profileEvents.length > 0) {
          const profile = parseProfile(profileEvents[0])
          setProfiles(prev => ({ ...prev, [targetPubkey]: profile }))
        }
      } catch (e) {
        console.error('Failed to load profile:', e)
      }
    }
    
    // Add to conversations if not exists (check inside setState to avoid race conditions)
    setConversations(prev => {
      // Check if already exists
      if (prev.find(c => c.pubkey === targetPubkey)) {
        return prev // Return unchanged
      }
      return [{
        pubkey: targetPubkey,
        timestamp: Math.floor(Date.now() / 1000)
      }, ...prev]
    })
    
    // Open the chat
    openChat(targetPubkey)
  }

  // Store ref for useImperativeHandle
  startNewChatRef.current = startNewChat

  // Expose methods for parent components
  useImperativeHandle(ref, () => ({
    startChat: (pubkey) => startNewChatRef.current?.(pubkey),
    refresh: loadConversations
  }))

  // Maximum images allowed
  const MAX_IMAGES = 3

  // Image handling - support multiple images
  const handleImageSelect = (e) => {
    const files = Array.from(e.target.files || [])
    if (files.length === 0) return

    const remainingSlots = MAX_IMAGES - imageFiles.length
    const filesToAdd = files.slice(0, remainingSlots)

    if (filesToAdd.length === 0) {
      alert(`最大${MAX_IMAGES}枚まで画像を追加できます`)
      return
    }

    // Read all files and create previews
    filesToAdd.forEach(file => {
      const reader = new FileReader()
      reader.onloadend = () => {
        setImagePreviews(prev => [...prev, reader.result])
      }
      reader.readAsDataURL(file)
    })

    setImageFiles(prev => [...prev, ...filesToAdd])

    // Reset input to allow selecting same file again
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  const handleRemoveImage = (index) => {
    setImageFiles(prev => prev.filter((_, i) => i !== index))
    setImagePreviews(prev => prev.filter((_, i) => i !== index))
  }

  // Emoji handling
  const handleEmojiSelect = (emoji) => {
    if (emoji.shortcode && emoji.url) {
      // Custom emoji
      setNewMessage(prev => prev + `:${emoji.shortcode}:`)
      setSelectedEmojis(prev => {
        if (!prev.find(e => e.shortcode === emoji.shortcode)) {
          return [...prev, emoji]
        }
        return prev
      })
    } else {
      // Standard emoji
      setNewMessage(prev => prev + emoji.native)
    }
    setShowEmojiPicker(false)
  }

  // Reset input state after sending
  const resetInputState = () => {
    setNewMessage('')
    setImageFiles([])
    setImagePreviews([])
    setSelectedEmojis([])
    setContentWarning('')
    setShowCWInput(false)
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  const handleSendMessage = async () => {
    if ((!newMessage.trim() && imageFiles.length === 0) || !selectedChat || sending) return
    setSending(true)

    let messageContent = newMessage.trim()
    const tempId = 'temp-' + Date.now()

    try {
      // Upload images if selected
      if (imageFiles.length > 0) {
        try {
          setUploadingImage(true)

          const { urls: uploadedUrls, errors } = await uploadImagesInParallel(imageFiles)

          if (errors.length > 0) {
            const errorMessages = errors.map(e => `${e.fileName}: ${e.error.message}`).join('\n')
            console.error('Some uploads failed:', errors)
            if (uploadedUrls.length === 0) {
              alert(`すべての画像のアップロードに失敗しました:\n${errorMessages}`)
              setSending(false)
              setUploadingImage(false)
              return
            } else {
              const continueWithPartial = confirm(
                `${errors.length}枚の画像のアップロードに失敗しました。\n` +
                `成功した${uploadedUrls.length}枚で送信を続けますか?`
              )
              if (!continueWithPartial) {
                setSending(false)
                setUploadingImage(false)
                return
              }
            }
          }

          // Append all image URLs to message
          if (uploadedUrls.length > 0) {
            const imageUrlsStr = uploadedUrls.join('\n')
            messageContent = messageContent ? `${messageContent}\n${imageUrlsStr}` : imageUrlsStr
          }
        } catch (e) {
          console.error('Image upload failed:', e)
          alert(`画像のアップロードに失敗しました: ${e.message}`)
          setSending(false)
          setUploadingImage(false)
          return
        } finally {
          setUploadingImage(false)
        }
      }

      // Add content warning prefix if set
      if (contentWarning.trim()) {
        messageContent = `[CW: ${contentWarning.trim()}]\n\n${messageContent}`
      }

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
      resetInputState()

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

  const handleStartNewChat = async () => {
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
    // Show notice if DM is not supported
    if (!hasDMSupport && !loading) {
      return (
        <div className="min-h-full">
          {/* Header */}
          <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
            <div className="flex items-center justify-center px-4 h-12">
              <h1 className="text-lg font-semibold text-[var(--text-primary)]">トーク</h1>
            </div>
          </header>
          
          <div className="px-4 py-16 text-center">
            <div className="w-20 h-20 mx-auto mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
              <svg className="w-10 h-10 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
                <circle cx="12" cy="15" r="1"/>
              </svg>
            </div>
            <h3 className="font-semibold text-[var(--text-primary)] mb-2">DM機能が利用できません</h3>
            <p className="text-sm text-[var(--text-secondary)] mb-4">
              DM機能を使用するにはNIP-44対応の拡張機能が必要です。
            </p>
          </div>
        </div>
      )
    }
    
    return (
      <div className="min-h-full">
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
                    onClick={handleStartNewChat}
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
            {/* Deduplicate conversations by pubkey */}
            {[...new Map(conversations.map(c => [c.pubkey, c])).values()].map((conv, index) => {
              const profile = profiles[conv.pubkey]
              return (
                <button
                  key={`conv-${conv.pubkey}`}
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
    <div className="h-full flex flex-col">
      {/* Chat Header */}
      <header className="flex-shrink-0 z-40 header-blur border-b border-[var(--border-color)]">
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
      <div className="flex-1 min-h-0 overflow-y-auto p-4 space-y-2 bg-[var(--bg-secondary)]">
        {messages.length === 0 && (
          <div className="text-center py-8">
            <p className="text-[var(--text-tertiary)] text-sm">メッセージを送信してみましょう</p>
          </div>
        )}
        {messages.map((msg, index) => (
          <div
            key={`${msg.id}-${index}`}
            className={`flex animate-fadeIn ${msg.isSent ? 'justify-end' : 'justify-start'}`}
            style={{ animationDelay: `${index * 20}ms` }}
          >
            <div className={msg.isSent ? 'message-sent' : 'message-received'}>
              <MessageContent content={msg.content} isSent={msg.isSent} />
              <p className={`text-xs mt-1 ${msg.isSent ? 'text-green-100' : 'text-[var(--text-tertiary)]'}`}>
                {msg.sending ? '送信中...' : formatTimestamp(msg.created_at)}
              </p>
            </div>
          </div>
        ))}
        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div className="flex-shrink-0 bg-[var(--bg-primary)] border-t border-[var(--border-color)]">
        {/* Content Warning Input */}
        {showCWInput && (
          <div className="px-3 pt-2 pb-1 border-b border-[var(--border-color)]">
            <div className="flex items-center gap-2 mb-1">
              <svg className="w-4 h-4 text-orange-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                <line x1="12" y1="9" x2="12" y2="13"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
              <span className="text-xs font-medium text-orange-500">コンテンツ警告</span>
            </div>
            <input
              type="text"
              value={contentWarning}
              onChange={(e) => setContentWarning(e.target.value)}
              className="w-full px-2 py-1.5 text-sm bg-[var(--bg-secondary)] border border-[var(--border-color)] rounded-lg text-[var(--text-primary)] placeholder:text-[var(--text-tertiary)] focus:outline-none focus:border-orange-500"
              placeholder="警告の理由"
              maxLength={100}
            />
          </div>
        )}

        {/* Image Previews - Multiple images */}
        {imagePreviews.length > 0 && (
          <div className="px-3 pt-2">
            <div className="flex flex-wrap gap-2">
              {imagePreviews.map((preview, index) => (
                <div key={index} className="relative inline-block">
                  <img
                    src={preview}
                    alt={`プレビュー ${index + 1}`}
                    className="h-20 w-20 rounded-lg object-cover"
                  />
                  <button
                    onClick={() => handleRemoveImage(index)}
                    className="absolute -top-1 -right-1 p-1 bg-black/60 rounded-full text-white hover:bg-black/80 transition-colors"
                  >
                    <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <line x1="18" y1="6" x2="6" y2="18" />
                      <line x1="6" y1="6" x2="18" y2="18" />
                    </svg>
                  </button>
                </div>
              ))}
              {/* Add more button if under limit (max 3) */}
              {imagePreviews.length < 3 && (
                <label
                  htmlFor="chat-image-input-add"
                  className="h-20 w-20 rounded-lg border-2 border-dashed border-[var(--border-color)] flex items-center justify-center cursor-pointer hover:border-[var(--line-green)] transition-colors"
                >
                  <svg className="w-6 h-6 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <line x1="12" y1="5" x2="12" y2="19" />
                    <line x1="5" y1="12" x2="19" y2="12" />
                  </svg>
                </label>
              )}
            </div>
            <input
              type="file"
              accept="image/*"
              multiple
              onChange={handleImageSelect}
              className="hidden"
              id="chat-image-input-add"
            />
          </div>
        )}

        {/* Toolbar */}
        <div className="flex items-center gap-1 px-2 pt-2">
          {/* Image upload */}
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            multiple
            onChange={handleImageSelect}
            className="hidden"
            id="chat-image-input"
          />
          <label
            htmlFor="chat-image-input"
            className={`action-btn p-2 cursor-pointer relative ${imageFiles.length >= 3 ? 'opacity-50 pointer-events-none' : ''}`}
          >
            <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
              <circle cx="8.5" cy="8.5" r="1.5" />
              <polyline points="21 15 16 10 5 21" />
            </svg>
            {imageFiles.length > 0 && (
              <span className="absolute -top-1 -right-1 bg-[var(--line-green)] text-white text-xs w-4 h-4 rounded-full flex items-center justify-center">
                {imageFiles.length}
              </span>
            )}
          </label>

          {/* CW toggle */}
          <button
            onClick={() => setShowCWInput(!showCWInput)}
            className={`action-btn p-2 ${showCWInput ? 'text-orange-500' : ''}`}
            title="コンテンツ警告"
          >
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
              <line x1="12" y1="9" x2="12" y2="13"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
          </button>

          {/* Emoji picker */}
          <div className="relative">
            <button
              onClick={() => setShowEmojiPicker(!showEmojiPicker)}
              className="action-btn p-2"
            >
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="12" r="10" />
                <path d="M8 14s1.5 2 4 2 4-2 4-2" />
                <line x1="9" y1="9" x2="9.01" y2="9" />
                <line x1="15" y1="9" x2="15.01" y2="9" />
              </svg>
            </button>
            {showEmojiPicker && (
              <>
                {/* Mobile: centered modal */}
                <div className="sm:hidden fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40" onClick={() => setShowEmojiPicker(false)}>
                  <div className="w-full max-w-sm" onClick={(e) => e.stopPropagation()}>
                    <EmojiPicker
                      onSelect={handleEmojiSelect}
                      onClose={() => setShowEmojiPicker(false)}
                      pubkey={pubkey}
                    />
                  </div>
                </div>
                {/* Desktop: dropdown */}
                <div className="hidden sm:block absolute right-0 bottom-full mb-2 z-50 w-80">
                  <EmojiPicker
                    onSelect={handleEmojiSelect}
                    onClose={() => setShowEmojiPicker(false)}
                    pubkey={pubkey}
                  />
                </div>
              </>
            )}
          </div>
        </div>

        {/* Text input and send button */}
        <div className="flex items-center gap-2 p-2">
          <div className="flex-1 relative">
            <textarea
              ref={textareaRef}
              value={newMessage}
              onChange={(e) => setNewMessage(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  handleSendMessage()
                }
              }}
              spellCheck={false}
              className={`w-full input-line py-2 resize-none min-h-[40px] max-h-[120px] ${
                newMessage && selectedEmojis.length > 0
                  ? 'text-transparent caret-[var(--text-primary)]'
                  : ''
              }`}
              placeholder="メッセージ"
              rows={1}
            />
            {/* Preview overlay for custom emojis */}
            {newMessage && selectedEmojis.length > 0 && (
              <div className="absolute inset-0 pointer-events-none px-4 py-2 overflow-hidden">
                <MessagePreview content={newMessage} customEmojis={selectedEmojis} />
              </div>
            )}
          </div>
          <button
            onClick={handleSendMessage}
            disabled={(!newMessage.trim() && imageFiles.length === 0) || sending || uploadingImage}
            className="w-10 h-10 rounded-full bg-[var(--line-green)] flex items-center justify-center disabled:opacity-50 action-btn flex-shrink-0"
          >
            {uploadingImage ? (
              <svg className="w-5 h-5 text-white animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="12" r="10" strokeDasharray="60" strokeDashoffset="20" />
              </svg>
            ) : (
              <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="currentColor">
                <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
              </svg>
            )}
          </button>
        </div>
      </div>
    </div>
  )
})

export default TalkTab
