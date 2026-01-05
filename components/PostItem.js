'use client'

import { useState, useEffect } from 'react'
import {
  shortenPubkey,
  formatTimestamp,
  parseNostrLink,
  fetchEvents,
  parseProfile,
  verifyNip05,
  encodeNpub,
  RELAYS
} from '@/lib/nostr'
import { getImageUrl } from '@/lib/imageUtils'
import BadgeDisplay from './BadgeDisplay'
import URLPreview from './URLPreview'
import ReportModal from './ReportModal'
import BirdwatchModal from './BirdwatchModal'
import BirdwatchDisplay from './BirdwatchDisplay'

// NIP-05 verified badge component
function Nip05Badge({ nip05, pubkey }) {
  const [verified, setVerified] = useState(false)
  const [checking, setChecking] = useState(true)

  useEffect(() => {
    if (!nip05 || !pubkey) {
      setChecking(false)
      return
    }

    let mounted = true
    verifyNip05(nip05, pubkey).then(result => {
      if (mounted) {
        setVerified(result)
        setChecking(false)
      }
    })

    return () => { mounted = false }
  }, [nip05, pubkey])

  if (!nip05 || checking) return null
  if (!verified) return null

  // Handle display format
  let display = nip05
  if (nip05.startsWith('_@')) {
    display = nip05.slice(1)
  } else if (!nip05.includes('@')) {
    display = `@${nip05}`
  }

  return (
    <span className="inline-flex items-center gap-0.5 text-xs text-[var(--line-green)]" title={`NIP-05: ${nip05}`}>
      <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="currentColor">
        <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/>
      </svg>
      <span className="truncate max-w-[120px]">{display}</span>
    </span>
  )
}

// Embedded note component for nostr:note1... and nostr:nevent1...
function EmbeddedNote({ noteId, relays }) {
  const [note, setNote] = useState(null)
  const [profile, setProfile] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  useEffect(() => {
    let mounted = true
    
    const loadNote = async () => {
      try {
        const events = await fetchEvents(
          { ids: [noteId], limit: 1 },
          relays?.length ? relays : RELAYS
        )
        
        if (!mounted) return
        
        if (events.length > 0) {
          setNote(events[0])
          
          const profileEvents = await fetchEvents(
            { kinds: [0], authors: [events[0].pubkey], limit: 1 },
            RELAYS
          )
          if (mounted && profileEvents.length > 0) {
            setProfile(parseProfile(profileEvents[0]))
          }
        } else {
          setError(true)
        }
      } catch (e) {
        if (mounted) setError(true)
      } finally {
        if (mounted) setLoading(false)
      }
    }

    loadNote()
    return () => { mounted = false }
  }, [noteId, relays])

  if (loading) {
    return (
      <div className="border border-[var(--border-color)] rounded-lg p-3 my-2 bg-[var(--bg-secondary)]">
        <div className="animate-pulse flex items-center gap-2">
          <div className="w-6 h-6 rounded-full bg-[var(--bg-tertiary)]" />
          <div className="h-3 w-24 bg-[var(--bg-tertiary)] rounded" />
        </div>
        <div className="animate-pulse mt-2 h-4 w-full bg-[var(--bg-tertiary)] rounded" />
      </div>
    )
  }

  if (error || !note) {
    return (
      <div className="border border-[var(--border-color)] rounded-lg p-3 my-2 bg-[var(--bg-secondary)] text-[var(--text-tertiary)] text-sm">
        📝 引用ノートを読み込めませんでした
      </div>
    )
  }

  // Extract images and URLs from content
  const extractMediaAndText = (content) => {
    if (!content) return { text: '', images: [], links: [] }
    
    const imageRegex = /https?:\/\/[^\s]+\.(jpg|jpeg|png|gif|webp)(\?[^\s]*)?/gi
    const urlRegex = /https?:\/\/[^\s]+/gi
    
    const images = content.match(imageRegex) || []
    const allUrls = content.match(urlRegex) || []
    const links = allUrls.filter(url => !images.includes(url))
    
    // Remove URLs from text
    let text = content.replace(urlRegex, '').trim()
    
    return { text, images, links }
  }

  const { text, images, links } = extractMediaAndText(note.content)

  return (
    <div className="border border-[var(--border-color)] rounded-lg p-3 my-2 bg-[var(--bg-secondary)]">
      <div className="flex items-center gap-2 mb-1.5">
        <div className="w-5 h-5 rounded-full overflow-hidden bg-[var(--bg-tertiary)] flex-shrink-0">
          {profile?.picture ? (
            <img
              src={getImageUrl(profile.picture)}
              alt=""
              className="w-full h-full object-cover"
              referrerPolicy="no-referrer"
              onError={(e) => {
                e.target.style.display = 'none'
                e.target.parentElement.innerHTML = '<div class="w-full h-full flex items-center justify-center"><svg class="w-3 h-3 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg></div>'
              }}
            />
          ) : (
            <div className="w-full h-full flex items-center justify-center">
              <svg className="w-3 h-3 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
              </svg>
            </div>
          )}
        </div>
        <span className="text-xs font-medium text-[var(--text-primary)] truncate">
          {profile?.name || shortenPubkey(note.pubkey, 6)}
        </span>
        {profile?.nip05 && (
          <Nip05Badge nip05={profile.nip05} pubkey={note.pubkey} />
        )}
        <span className="text-xs text-[var(--text-tertiary)]">
          · {formatTimestamp(note.created_at)}
        </span>
      </div>
      
      {/* Text content */}
      {text && (
        <p className="text-sm text-[var(--text-primary)] whitespace-pre-wrap break-words line-clamp-3">
          {text.slice(0, 200)}{text.length > 200 ? '...' : ''}
        </p>
      )}
      
      {/* Images */}
      {images.length > 0 && (
        <div className={`mt-2 ${images.length > 1 ? 'grid grid-cols-2 gap-1' : ''}`}>
          {images.slice(0, 2).map((img, i) => (
            <img
              key={i}
              src={getImageUrl(img)}
              alt=""
              className="rounded-md max-h-32 w-full object-cover"
              loading="lazy"
              referrerPolicy="no-referrer"
            />
          ))}
          {images.length > 2 && (
            <div className="text-xs text-[var(--text-tertiary)] mt-1">
              +{images.length - 2}枚の画像
            </div>
          )}
        </div>
      )}
      
      {/* Links */}
      {links.length > 0 && (
        <div className="mt-2 text-xs">
          {links.slice(0, 1).map((link, i) => (
            <a 
              key={i}
              href={link} 
              target="_blank" 
              rel="noopener noreferrer"
              className="text-[var(--line-green)] hover:underline break-all"
              onClick={(e) => e.stopPropagation()}
            >
              🔗 {link.length > 40 ? link.slice(0, 40) + '...' : link}
            </a>
          ))}
        </div>
      )}
    </div>
  )
}

// Embedded profile component for nostr:npub1... and nostr:nprofile1...
function EmbeddedProfile({ pubkey, relays }) {
  const [profile, setProfile] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let mounted = true
    
    const loadProfile = async () => {
      try {
        const events = await fetchEvents(
          { kinds: [0], authors: [pubkey], limit: 1 },
          relays?.length ? relays : RELAYS
        )
        if (mounted && events.length > 0) {
          setProfile(parseProfile(events[0]))
        }
      } catch (e) {
        console.log('Failed to load profile:', e)
      } finally {
        if (mounted) setLoading(false)
      }
    }

    loadProfile()
    return () => { mounted = false }
  }, [pubkey, relays])

  const npub = encodeNpub(pubkey)

  if (loading) {
    return (
      <span className="inline-flex items-center gap-1 text-[var(--line-green)]">
        @...
      </span>
    )
  }

  return (
    <span className="inline-flex items-center gap-0.5 text-[var(--line-green)] hover:underline cursor-pointer" title={npub}>
      @{profile?.name || shortenPubkey(pubkey, 6)}
    </span>
  )
}

// Main PostItem component
export default function PostItem({
  post,
  profile,
  profiles,
  likeCount = 0,
  hasLiked = false,
  hasReposted = false,
  myReactionId = null,
  myRepostId = null,
  isLiking = false,
  isZapping = false,
  onLike,
  onUnlike,
  onRepost,
  onUnrepost,
  onZap,
  onZapLongPress,
  onZapLongPressEnd,
  onAvatarClick,
  onHashtagClick,
  onMute,
  onDelete,
  onReport,
  onBirdwatch,
  onBirdwatchRate,
  birdwatchNotes = [],
  myPubkey,
  isOwnPost = false,
  isRepost = false,
  repostedBy = null,
  showActions = true
}) {
  const [showMenu, setShowMenu] = useState(false)
  const [isExpanded, setIsExpanded] = useState(false)
  const [isCWExpanded, setIsCWExpanded] = useState(false) // Content warning expand state
  const [showReportModal, setShowReportModal] = useState(false)
  const [showBirdwatchModal, setShowBirdwatchModal] = useState(false)
  const displayProfile = isRepost ? profiles?.[post.pubkey] : profile

  // Extract content warning tag (NIP-36)
  const cwTag = post.tags?.find(t => t[0] === 'content-warning')
  const contentWarning = cwTag ? (cwTag[1] || '') : null
  const hasCW = contentWarning !== null
  
  // Content length threshold for collapsing (excluding URLs)
  const COLLAPSE_THRESHOLD = 140
  
  // Calculate content length excluding URLs and nostr links
  const getTextLengthWithoutLinks = (content) => {
    if (!content) return 0
    // Remove URLs and nostr: links
    const withoutLinks = content
      .replace(/https?:\/\/[^\s]+/gi, '')
      .replace(/nostr:[a-z0-9]+/gi, '')
      .trim()
    return withoutLinks.length
  }
  
  const textLength = getTextLengthWithoutLinks(post.content)
  const shouldCollapse = textLength > COLLAPSE_THRESHOLD
  
  // Check if this is a reply
  const isReply = post.tags?.some(t => t[0] === 'e')
  
  // Get reply target pubkey
  const replyTargetPubkey = isReply ? post.tags?.find(t => t[0] === 'p')?.[1] : null
  const replyTargetProfile = replyTargetPubkey ? profiles?.[replyTargetPubkey] : null
  
  // Extract custom emoji map from tags (NIP-30)
  const customEmojiMap = {}
  if (post.tags) {
    for (const tag of post.tags) {
      if (tag[0] === 'emoji' && tag[1] && tag[2]) {
        customEmojiMap[tag[1]] = tag[2]
      }
    }
  }
  
  // Extract client tag
  const clientTag = post.tags?.find(t => t[0] === 'client')?.[1] || null
  
  // Replace custom emoji shortcodes with images
  const emojifyContent = (text) => {
    if (!text || Object.keys(customEmojiMap).length === 0) return text
    
    // Match :shortcode: pattern
    const emojiRegex = /:([a-zA-Z0-9_]+):/g
    const parts = []
    let lastIndex = 0
    let match
    
    while ((match = emojiRegex.exec(text)) !== null) {
      const shortcode = match[1]
      const emojiUrl = customEmojiMap[shortcode]
      
      // Add text before this match
      if (match.index > lastIndex) {
        parts.push(text.slice(lastIndex, match.index))
      }
      
      // Add emoji image or keep original text
      if (emojiUrl) {
        parts.push(
          <img
            key={`emoji-${match.index}`}
            src={getImageUrl(emojiUrl)}
            alt={`:${shortcode}:`}
            title={`:${shortcode}:`}
            className="inline-block w-5 h-5 align-middle mx-0.5"
            loading="lazy"
            referrerPolicy="no-referrer"
          />
        )
      } else {
        parts.push(match[0])
      }
      
      lastIndex = match.index + match[0].length
    }
    
    // Add remaining text
    if (lastIndex < text.length) {
      parts.push(text.slice(lastIndex))
    }
    
    return parts.length > 0 ? parts : text
  }
  
  // State for expanded images
  const [showAllImages, setShowAllImages] = useState(false)

  // Render content with nostr: links, URLs, images, hashtags, and custom emoji
  const renderContent = (content) => {
    if (!content) return null

    const combinedRegex = /(https?:\/\/[^\s]+|nostr:(?:note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]{58,}|#[^\s#\u3000]+)/gi
    const parts = content.split(combinedRegex).filter(Boolean)

    // Collect images, videos, and other content separately
    const images = []
    const videos = []
    const previewUrls = []
    const textParts = []

    parts.forEach((part, i) => {
      // Check for hashtags
      if (part.startsWith('#') && part.length > 1) {
        const hashtag = part.slice(1)
        textParts.push(
          <span
            key={`text-${i}`}
            onClick={(e) => {
              e.stopPropagation()
              if (onHashtagClick) onHashtagClick(hashtag)
            }}
            className="text-[var(--line-green)] hover:underline cursor-pointer"
          >
            {part}
          </span>
        )
        return
      }

      // Check for nostr: links
      if (part.toLowerCase().startsWith('nostr:')) {
        const bech32 = part.slice(6)
        if (bech32.length < 59) {
          textParts.push(<span key={`text-${i}`}>{part}</span>)
          return
        }
        const parsed = parseNostrLink(bech32)
        if (parsed) {
          if (parsed.type === 'note') {
            textParts.push(<EmbeddedNote key={`text-${i}`} noteId={parsed.id} />)
            return
          }
          if (parsed.type === 'nevent') {
            textParts.push(<EmbeddedNote key={`text-${i}`} noteId={parsed.id} relays={parsed.relays} />)
            return
          }
          if (parsed.type === 'npub') {
            textParts.push(<EmbeddedProfile key={`text-${i}`} pubkey={parsed.pubkey} />)
            return
          }
          if (parsed.type === 'nprofile') {
            textParts.push(<EmbeddedProfile key={`text-${i}`} pubkey={parsed.pubkey} relays={parsed.relays} />)
            return
          }
        }
        textParts.push(<span key={`text-${i}`} className="text-[var(--line-green)]">{part}</span>)
        return
      }

      // Check for image URLs
      if (part.match(/^https?:\/\/.*\.(jpg|jpeg|png|gif|webp)(\?.*)?$/i)) {
        images.push(part)
        return
      }

      // Check for video URLs
      if (part.match(/^https?:\/\/.*\.(mp4|webm|mov)(\?.*)?$/i)) {
        videos.push(part)
        return
      }

      // Check for regular URLs
      if (part.match(/^https?:\/\//)) {
        if (!previewUrls.includes(part)) {
          previewUrls.push(part)
        }
        textParts.push(
          <a
            key={`text-${i}`}
            href={part}
            target="_blank"
            rel="noopener noreferrer"
            className="text-[var(--line-green)] hover:underline break-all"
          >
            {part.length > 50 ? part.slice(0, 50) + '...' : part}
          </a>
        )
        return
      }

      // Apply custom emoji to text parts
      const emojified = emojifyContent(part)
      textParts.push(<span key={`text-${i}`}>{emojified}</span>)
    })

    // Determine how many images to show
    const maxVisibleImages = 4
    const visibleImages = showAllImages ? images : images.slice(0, maxVisibleImages)
    const hiddenCount = images.length - maxVisibleImages

    // Render images in grid layout
    const renderImages = () => {
      if (images.length === 0) return null

      // Single image - full width
      if (images.length === 1) {
        return (
          <div className="mt-2">
            <img
              src={getImageUrl(images[0])}
              alt=""
              className="rounded-lg max-h-72 w-full object-cover cursor-pointer"
              loading="lazy"
              referrerPolicy="no-referrer"
              onClick={(e) => {
                e.stopPropagation()
                window.open(images[0], '_blank')
              }}
            />
          </div>
        )
      }

      // Multiple images - grid layout
      return (
        <div className="mt-2 grid gap-1" style={{
          gridTemplateColumns: images.length === 2 ? 'repeat(2, 1fr)' : 'repeat(2, 1fr)',
        }}>
          {visibleImages.map((url, idx) => (
            <div
              key={idx}
              className={`relative ${
                images.length === 3 && idx === 0 ? 'row-span-2' : ''
              }`}
            >
              <img
                src={getImageUrl(url)}
                alt=""
                className={`rounded-lg w-full object-cover cursor-pointer ${
                  images.length === 3 && idx === 0 ? 'h-full' : 'h-36'
                }`}
                style={{ maxHeight: images.length === 3 && idx === 0 ? '296px' : '144px' }}
                loading="lazy"
                referrerPolicy="no-referrer"
                onClick={(e) => {
                  e.stopPropagation()
                  window.open(url, '_blank')
                }}
              />
              {/* Show "+X" overlay on last visible image if there are hidden images */}
              {!showAllImages && hiddenCount > 0 && idx === maxVisibleImages - 1 && (
                <div
                  className="absolute inset-0 bg-black/60 rounded-lg flex items-center justify-center cursor-pointer"
                  onClick={(e) => {
                    e.stopPropagation()
                    setShowAllImages(true)
                  }}
                >
                  <span className="text-white text-2xl font-bold">+{hiddenCount}</span>
                </div>
              )}
            </div>
          ))}
          {/* Show remaining images when expanded */}
          {showAllImages && images.slice(maxVisibleImages).map((url, idx) => (
            <div key={`extra-${idx}`} className="relative">
              <img
                src={getImageUrl(url)}
                alt=""
                className="rounded-lg w-full h-36 object-cover cursor-pointer"
                loading="lazy"
                referrerPolicy="no-referrer"
                onClick={(e) => {
                  e.stopPropagation()
                  window.open(url, '_blank')
                }}
              />
            </div>
          ))}
        </div>
      )
    }

    // Render videos
    const renderVideos = () => {
      if (videos.length === 0) return null
      return videos.map((url, idx) => (
        <video
          key={`video-${idx}`}
          src={url}
          controls
          className="mt-2 rounded-lg max-h-72 w-full"
          onClick={(e) => e.stopPropagation()}
        />
      ))
    }

    return (
      <>
        {textParts}
        {renderImages()}
        {renderVideos()}
        {/* Show URL previews for non-media URLs (max 2) */}
        {previewUrls.slice(0, 2).map((url, i) => (
          <URLPreview key={`preview-${i}`} url={url} />
        ))}
      </>
    )
  }

  const handleAvatarClick = (e) => {
    e.stopPropagation()
    if (onAvatarClick) {
      onAvatarClick(post.pubkey, displayProfile)
    }
  }

  const handleMute = () => {
    setShowMenu(false)
    if (onMute) {
      onMute(post.pubkey)
    }
  }

  const handleDelete = () => {
    setShowMenu(false)
    if (onDelete) {
      onDelete(post.id)
    }
  }

  const handleLikeClick = () => {
    if (hasLiked && myReactionId && onUnlike) {
      onUnlike(post, myReactionId)
    } else if (!hasLiked && onLike) {
      onLike(post)
    }
  }

  const handleRepostClick = () => {
    if (hasReposted && myRepostId && onUnrepost) {
      onUnrepost(post, myRepostId)
    } else if (!hasReposted && onRepost) {
      onRepost(post)
    }
  }

  const handleReport = () => {
    setShowMenu(false)
    setShowReportModal(true)
  }

  const handleBirdwatch = () => {
    setShowMenu(false)
    setShowBirdwatchModal(true)
  }

  const handleReportSubmit = async (reportData) => {
    if (onReport) {
      await onReport(reportData)
    }
  }

  const handleBirdwatchSubmit = async (birdwatchData) => {
    if (onBirdwatch) {
      await onBirdwatch(birdwatchData)
    }
  }

  return (
    <article className="px-4 py-3 lg:px-5 lg:py-4 relative transition-colors hover:bg-[var(--bg-secondary)]/30">
      {/* Repost indicator */}
      {isRepost && repostedBy && (
        <div className="flex items-center gap-2 mb-2 text-[var(--text-tertiary)] text-xs">
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="17 1 21 5 17 9"/>
            <path d="M3 11V9a4 4 0 014-4h14"/>
            <polyline points="7 23 3 19 7 15"/>
            <path d="M21 13v2a4 4 0 01-4 4H3"/>
          </svg>
          <span>{repostedBy.name || shortenPubkey(repostedBy.pubkey, 6)} がリポスト</span>
        </div>
      )}
      
      {/* Reply indicator */}
      {isReply && !isRepost && (
        <div className="flex items-center gap-2 mb-2 text-[var(--text-tertiary)] text-xs">
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z"/>
          </svg>
          <span>
            {replyTargetPubkey ? (
              <>
                <span className="text-[var(--line-green)]">
                  @{replyTargetProfile?.name || shortenPubkey(replyTargetPubkey, 6)}
                </span>
                <span> への返信</span>
              </>
            ) : (
              'リプライ'
            )}
          </span>
        </div>
      )}
      
      <div className="flex items-start gap-3 lg:gap-4">
        {/* Avatar - clickable */}
        <button 
          onClick={handleAvatarClick}
          className="w-10 h-10 lg:w-12 lg:h-12 rounded-full overflow-hidden bg-[var(--bg-tertiary)] flex-shrink-0 hover:opacity-80 transition-opacity"
        >
          {displayProfile?.picture ? (
            <img
              src={getImageUrl(displayProfile.picture)}
              alt=""
              className="w-full h-full object-cover"
              referrerPolicy="no-referrer"
              onError={(e) => {
                e.target.style.display = 'none'
                e.target.parentElement.innerHTML = '<div class="w-full h-full flex items-center justify-center"><svg class="w-5 h-5 lg:w-6 lg:h-6 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg></div>'
              }}
            />
          ) : (
            <div className="w-full h-full flex items-center justify-center">
              <svg className="w-5 h-5 lg:w-6 lg:h-6 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
              </svg>
            </div>
          )}
        </button>
        
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1 lg:gap-2 mb-0.5">
            {/* Username - always show at least truncated */}
            <button 
              onClick={handleAvatarClick}
              className="font-semibold text-[var(--text-primary)] text-sm lg:text-base truncate hover:underline flex-shrink min-w-[40px]"
              style={{ maxWidth: '150px' }}
            >
              {displayProfile?.name || 'Anonymous'}
            </button>
            {/* NIP-05 checkmark only on mobile */}
            {displayProfile?.nip05 && (
              <span className="sm:hidden text-[var(--line-green)] flex-shrink-0">
                <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/>
                </svg>
              </span>
            )}
            {/* Profile badges (max 3) */}
            <BadgeDisplay pubkey={post.pubkey} maxBadges={3} />
            {/* NIP-05 badge - full on desktop */}
            {displayProfile?.nip05 && (
              <div className="hidden sm:block flex-shrink-0">
                <Nip05Badge nip05={displayProfile.nip05} pubkey={post.pubkey} />
              </div>
            )}
            <span className="text-xs text-[var(--text-tertiary)] whitespace-nowrap ml-auto flex-shrink-0">
              {formatTimestamp(post.created_at)}
            </span>
            
            {/* Menu button */}
            <div className="relative flex-shrink-0">
              <button
                onClick={() => setShowMenu(!showMenu)}
                className="p-1 text-[var(--text-tertiary)] hover:text-[var(--text-primary)] action-btn"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                  <circle cx="12" cy="5" r="2"/>
                  <circle cx="12" cy="12" r="2"/>
                  <circle cx="12" cy="19" r="2"/>
                </svg>
              </button>

              {/* Dropdown menu */}
              {showMenu && (
                <>
                  <div
                    className="fixed inset-0 z-40"
                    onClick={() => setShowMenu(false)}
                  />
                  <div className="absolute right-0 top-6 z-50 bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-lg shadow-lg py-1 min-w-[160px]">
                    {/* Birdwatch button */}
                    {onBirdwatch && !isOwnPost && (
                      <button
                        onClick={handleBirdwatch}
                        className="w-full px-4 py-2 text-left text-sm text-blue-500 hover:bg-[var(--bg-secondary)] flex items-center gap-2"
                      >
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
                          <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
                        </svg>
                        Birdwatch
                      </button>
                    )}
                    {/* Report button */}
                    {onReport && !isOwnPost && (
                      <button
                        onClick={handleReport}
                        className="w-full px-4 py-2 text-left text-sm text-orange-500 hover:bg-[var(--bg-secondary)] flex items-center gap-2"
                      >
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                          <path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/>
                          <line x1="4" y1="22" x2="4" y2="15"/>
                        </svg>
                        通報
                      </button>
                    )}
                    {/* Mute button */}
                    {onMute && !isOwnPost && (
                      <button
                        onClick={handleMute}
                        className="w-full px-4 py-2 text-left text-sm text-red-500 hover:bg-[var(--bg-secondary)] flex items-center gap-2"
                      >
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                          <circle cx="12" cy="12" r="10"/>
                          <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
                        </svg>
                        ミュート
                      </button>
                    )}
                    {/* Delete button */}
                    {isOwnPost && onDelete && (
                      <button
                        onClick={handleDelete}
                        className="w-full px-4 py-2 text-left text-sm text-red-500 hover:bg-[var(--bg-secondary)] flex items-center gap-2"
                      >
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                          <polyline points="3 6 5 6 21 6"/>
                          <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/>
                          <line x1="10" y1="11" x2="10" y2="17"/>
                          <line x1="14" y1="11" x2="14" y2="17"/>
                        </svg>
                        削除
                      </button>
                    )}
                  </div>
                </>
              )}
            </div>
          </div>
          
          {/* Content Warning Display (NIP-36) */}
          {hasCW && !isCWExpanded ? (
            <div className="border border-orange-500/30 bg-orange-500/5 rounded-lg p-3">
              <div className="flex items-center gap-2 text-orange-500">
                <svg className="w-4 h-4 flex-shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                  <line x1="12" y1="9" x2="12" y2="13"/>
                  <line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
                <span className="text-sm font-medium">
                  {contentWarning || 'コンテンツ警告'}
                </span>
              </div>
              <button
                onClick={(e) => { e.stopPropagation(); setIsCWExpanded(true) }}
                className="mt-2 px-3 py-1.5 text-xs font-medium bg-orange-500/20 text-orange-600 dark:text-orange-400 rounded-full hover:bg-orange-500/30 transition-colors"
              >
                表示する
              </button>
            </div>
          ) : (
            <div className="text-[var(--text-primary)] text-sm whitespace-pre-wrap break-words">
              {/* Show CW indicator when expanded */}
              {hasCW && isCWExpanded && (
                <div className="flex items-center gap-2 mb-2 text-orange-500 text-xs">
                  <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                    <line x1="12" y1="9" x2="12" y2="13"/>
                    <line x1="12" y1="17" x2="12.01" y2="17"/>
                  </svg>
                  <span className="font-medium">{contentWarning || 'コンテンツ警告'}</span>
                  <button
                    onClick={(e) => { e.stopPropagation(); setIsCWExpanded(false) }}
                    className="ml-auto text-[var(--text-tertiary)] hover:text-orange-500"
                  >
                    隠す
                  </button>
                </div>
              )}
              {shouldCollapse && !isExpanded ? (
                <>
                  {/* Show more content when there are URLs (since they don't count toward limit) */}
                  {renderContent(post.content.slice(0, 280) + '...')}
                  <button
                    onClick={(e) => { e.stopPropagation(); setIsExpanded(true) }}
                    className="text-[var(--line-green)] text-xs mt-1 hover:underline"
                  >
                    もっと見る
                  </button>
                </>
              ) : (
                <>
                  {renderContent(post.content)}
                  {shouldCollapse && isExpanded && (
                    <button
                      onClick={(e) => { e.stopPropagation(); setIsExpanded(false) }}
                      className="text-[var(--line-green)] text-xs mt-1 hover:underline block"
                    >
                      閉じる
                    </button>
                  )}
                </>
              )}
            </div>
          )}
          
          {/* Actions */}
          {showActions && (
            <div className="flex items-center justify-between mt-3">
              <div className="flex items-center gap-8">
                {/* Like - Thumbs Up */}
                <button
                  onClick={handleLikeClick}
                  className={`action-btn flex items-center gap-1.5 text-sm ${
                    hasLiked ? 'text-[var(--line-green)]' : 'text-[var(--text-tertiary)]'
                  } ${isLiking ? 'like-animation' : ''}`}
                >
                  <svg className="w-5 h-5" viewBox="0 0 24 24" fill={hasLiked ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8">
                    <path d="M14 9V5a3 3 0 00-3-3l-4 9v11h11.28a2 2 0 002-1.7l1.38-9a2 2 0 00-2-2.3H14zM7 22H4a2 2 0 01-2-2v-7a2 2 0 012-2h3"/>
                  </svg>
                  {likeCount > 0 && <span>{likeCount}</span>}
                </button>

                {/* Repost */}
                <button
                  onClick={handleRepostClick}
                  className={`action-btn flex items-center gap-1.5 text-sm ${
                    hasReposted ? 'text-green-500' : 'text-[var(--text-tertiary)]'
                  }`}
                >
                  <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="17 1 21 5 17 9"/>
                    <path d="M3 11V9a4 4 0 014-4h14"/>
                    <polyline points="7 23 3 19 7 15"/>
                    <path d="M21 13v2a4 4 0 01-4 4H3"/>
                  </svg>
                </button>

                {/* Zap */}
                <button
                  onClick={() => onZap?.(post)}
                  onTouchStart={() => onZapLongPress?.(post)}
                  onTouchEnd={() => onZapLongPressEnd?.()}
                  onMouseDown={() => onZapLongPress?.(post)}
                  onMouseUp={() => onZapLongPressEnd?.()}
                  onMouseLeave={() => onZapLongPressEnd?.()}
                  className={`action-btn flex items-center gap-1.5 text-sm text-[var(--text-tertiary)] ${
                    isZapping ? 'zap-animation text-yellow-500' : ''
                  }`}
                >
                  <svg className="w-5 h-5" viewBox="0 0 24 24" fill={isZapping ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
                  </svg>
                </button>
              </div>
              
              {/* Client tag (via) */}
              {clientTag && (
                <span className="text-[10px] text-[var(--text-tertiary)] opacity-60">
                  via {clientTag}
                </span>
              )}
            </div>
          )}

          {/* Birdwatch Display (Community Notes) */}
          {birdwatchNotes.length > 0 && (
            <BirdwatchDisplay
              notes={birdwatchNotes}
              profiles={profiles}
              onRate={onBirdwatchRate}
              onAuthorClick={onAvatarClick}
              compact={true}
            />
          )}
        </div>
      </div>

      {/* Report Modal */}
      <ReportModal
        isOpen={showReportModal}
        onClose={() => setShowReportModal(false)}
        onReport={handleReportSubmit}
        targetEvent={post}
        targetPubkey={post.pubkey}
      />

      {/* Birdwatch Modal */}
      <BirdwatchModal
        isOpen={showBirdwatchModal}
        onClose={() => setShowBirdwatchModal(false)}
        onSubmit={handleBirdwatchSubmit}
        targetEvent={post}
        existingNotes={birdwatchNotes}
        profiles={profiles}
        myPubkey={myPubkey}
      />
    </article>
  )
}
