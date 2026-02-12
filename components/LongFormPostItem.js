'use client'

import { useState, useEffect, useRef, useCallback } from 'react'
import { createPortal } from 'react-dom'
import { marked } from 'marked'
import {
  shortenPubkey,
  formatTimestamp
} from '@/lib/nostr'
import { getImageUrl } from '@/lib/imageUtils'
import ReactionEmojiPicker from './ReactionEmojiPicker'

// Configure marked for NIP-23: no HTML allowed in markdown
marked.use({
  breaks: true,
  gfm: true,
  renderer: {
    // Override html to strip raw HTML (NIP-23: MUST NOT support adding HTML to Markdown)
    html() {
      return ''
    }
  }
})

// Simple HTML sanitizer - strips dangerous tags/attributes while keeping safe ones
function sanitizeHtml(html) {
  // Remove script, style, iframe, object, embed tags entirely
  let clean = html.replace(/<(script|style|iframe|object|embed|form|input|button)[^>]*>[\s\S]*?<\/\1>/gi, '')
  clean = clean.replace(/<(script|style|iframe|object|embed|form|input|button)[^>]*\/?>/gi, '')
  // Remove event handlers (on*)
  clean = clean.replace(/\s+on\w+\s*=\s*["'][^"']*["']/gi, '')
  clean = clean.replace(/\s+on\w+\s*=\s*\S+/gi, '')
  // Remove javascript: URLs
  clean = clean.replace(/href\s*=\s*["']javascript:[^"']*["']/gi, 'href="#"')
  return clean
}

// Extract NIP-23 tags from event
function extractLongFormTags(event) {
  const tags = event.tags || []
  const getTag = (name) => tags.find(t => t[0] === name)?.[1] || null

  return {
    title: getTag('title'),
    image: getTag('image'),
    summary: getTag('summary'),
    publishedAt: getTag('published_at'),
    identifier: getTag('d'),
    hashtags: tags.filter(t => t[0] === 't').map(t => t[1])
  }
}

// Render markdown content
function renderMarkdown(content) {
  if (!content) return ''
  try {
    const rawHtml = marked.parse(content)
    return sanitizeHtml(rawHtml)
  } catch (e) {
    return content
  }
}

// Full-screen article reader modal
function ArticleReaderModal({ post, profile, title, image, publishedAt, hashtags, onClose, onHashtagClick }) {
  const displayTime = publishedAt ? parseInt(publishedAt, 10) : post.created_at

  // Lock body scroll
  useEffect(() => {
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = '' }
  }, [])

  return createPortal(
    <div className="fixed inset-0 z-[60] bg-[var(--bg-primary)] flex flex-col">
      {/* Header */}
      <header className="flex-shrink-0 border-b border-[var(--border-color)] header-blur">
        <div className="flex items-center gap-3 px-4 h-14 lg:h-16 max-w-3xl mx-auto w-full">
          <button
            onClick={onClose}
            className="w-10 h-10 flex items-center justify-center action-btn flex-shrink-0 -ml-2"
          >
            <svg className="w-6 h-6 text-[var(--text-primary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="15 18 9 12 15 6"/>
            </svg>
          </button>
          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-[var(--line-green)]/10 text-[var(--line-green)] text-xs font-medium">
            <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
              <line x1="16" y1="13" x2="8" y2="13"/>
              <line x1="16" y1="17" x2="8" y2="17"/>
              <polyline points="10 9 9 9 8 9"/>
            </svg>
            長文記事
          </span>
          <span className="ml-auto text-xs text-[var(--text-tertiary)]">{formatTimestamp(displayTime)}</span>
        </div>
      </header>

      {/* Scrollable article body */}
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-3xl mx-auto w-full px-4 lg:px-8 py-6 lg:py-8">
          {/* Featured image */}
          {image && (
            <div className="w-full rounded-xl overflow-hidden mb-6 max-h-80 lg:max-h-96">
              <img
                src={getImageUrl(image)}
                alt={title || ''}
                className="w-full h-full object-cover"
                referrerPolicy="no-referrer"
                onError={(e) => { e.target.parentElement.style.display = 'none' }}
              />
            </div>
          )}

          {/* Title */}
          {title && (
            <h1 className="text-xl lg:text-2xl font-bold text-[var(--text-primary)] mb-4 leading-tight">
              {title}
            </h1>
          )}

          {/* Author info */}
          <div className="flex items-center gap-3 mb-6 pb-6 border-b border-[var(--border-color)]">
            <div className="w-10 h-10 rounded-full overflow-hidden bg-[var(--bg-tertiary)] flex-shrink-0">
              {profile?.picture ? (
                <img
                  src={getImageUrl(profile.picture)}
                  alt=""
                  className="w-full h-full object-cover"
                  referrerPolicy="no-referrer"
                  onError={(e) => { e.target.style.display = 'none' }}
                />
              ) : (
                <div className="w-full h-full flex items-center justify-center">
                  <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                  </svg>
                </div>
              )}
            </div>
            <div>
              <p className="font-semibold text-sm text-[var(--text-primary)]">
                {profile?.name || shortenPubkey(post.pubkey, 8)}
              </p>
              {profile?.nip05 && (
                <p className="text-xs text-[var(--text-tertiary)]">{profile.nip05}</p>
              )}
            </div>
          </div>

          {/* Markdown content */}
          <div
            className="long-form-content text-[var(--text-primary)] leading-relaxed"
            dangerouslySetInnerHTML={{ __html: renderMarkdown(post.content) }}
          />

          {/* Hashtags */}
          {hashtags.length > 0 && (
            <div className="flex flex-wrap gap-2 mt-8 pt-6 border-t border-[var(--border-color)]">
              {hashtags.map((tag, i) => (
                <span
                  key={i}
                  onClick={() => { onClose(); onHashtagClick?.(tag) }}
                  className="text-sm text-[var(--line-green)] cursor-pointer hover:underline px-2 py-1 rounded-full bg-[var(--line-green)]/10"
                >
                  #{tag}
                </span>
              ))}
            </div>
          )}

          {/* Bottom spacer */}
          <div className="h-16" />
        </div>
      </div>
    </div>,
    document.body
  )
}

export default function LongFormPostItem({
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
  onNotInterested,
  birdwatchNotes = [],
  myPubkey,
  isOwnPost = false,
  isRepost = false,
  repostedBy = null,
  showActions = true,
  showNotInterested = false
}) {
  const [showArticle, setShowArticle] = useState(false)
  const [showMenu, setShowMenu] = useState(false)
  const [showReactionPicker, setShowReactionPicker] = useState(false)
  const longPressTimerRef = useRef(null)
  const longPressTriggeredRef = useRef(false)
  const displayProfile = isRepost ? profiles?.[post.pubkey] : profile

  const { title, image, summary, publishedAt, hashtags } = extractLongFormTags(post)

  // Display timestamp - prefer published_at, fallback to created_at
  const displayTime = publishedAt ? parseInt(publishedAt, 10) : post.created_at

  const handleAvatarClick = (e) => {
    e.stopPropagation()
    if (onAvatarClick) {
      onAvatarClick(post.pubkey, displayProfile)
    }
  }

  const handleLikeClick = () => {
    if (longPressTriggeredRef.current) {
      longPressTriggeredRef.current = false
      return
    }
    if (hasLiked && myReactionId && onUnlike) {
      onUnlike(post, myReactionId)
    } else if (!hasLiked && onLike) {
      onLike(post)
    }
  }

  const handleLikeLongPressStart = useCallback(() => {
    longPressTriggeredRef.current = false
    longPressTimerRef.current = setTimeout(() => {
      longPressTriggeredRef.current = true
      setShowReactionPicker(true)
    }, 500)
  }, [])

  const handleLikeLongPressEnd = useCallback(() => {
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current)
      longPressTimerRef.current = null
    }
  }, [])

  const handleReactionSelect = (emoji) => {
    setShowReactionPicker(false)
    if (onLike) {
      onLike(post, emoji)
    }
  }

  const handleRepostClick = () => {
    if (hasReposted && myRepostId && onUnrepost) {
      onUnrepost(post, myRepostId)
    } else if (!hasReposted && onRepost) {
      onRepost(post)
    }
  }

  const handleMute = () => {
    setShowMenu(false)
    if (onMute) onMute(post.pubkey)
  }

  const handleDelete = () => {
    setShowMenu(false)
    if (onDelete) onDelete(post.id)
  }

  // Get client tag
  const clientTag = post.tags?.find(t => t[0] === 'client')?.[1] || null

  // Truncated summary for card view
  const displaySummary = summary || (post.content ? post.content.slice(0, 200) + (post.content.length > 200 ? '...' : '') : '')

  return (
    <>
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

        <div className="flex items-start gap-3 lg:gap-4">
          {/* Avatar */}
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
            {/* Header row */}
            <div className="flex items-center gap-1 lg:gap-2 mb-0.5">
              <button
                onClick={handleAvatarClick}
                className="font-semibold text-[var(--text-primary)] text-sm lg:text-base truncate hover:underline flex-shrink min-w-[40px]"
                style={{ maxWidth: '150px' }}
              >
                {displayProfile?.name || 'Anonymous'}
              </button>
              <span className="text-xs text-[var(--text-tertiary)] whitespace-nowrap ml-auto flex-shrink-0">
                {formatTimestamp(displayTime)}
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

                {showMenu && (
                  <>
                    <div className="fixed inset-0 z-40" onClick={() => setShowMenu(false)} />
                    <div className="absolute right-0 top-6 z-50 bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-lg shadow-lg py-1 min-w-[160px]">
                      {showNotInterested && onNotInterested && !isOwnPost && (
                        <button
                          onClick={() => { onNotInterested(post.id, post.pubkey); setShowMenu(false) }}
                          className="w-full px-4 py-2 text-left text-sm text-[var(--text-secondary)] hover:bg-[var(--bg-secondary)] flex items-center gap-2"
                        >
                          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <circle cx="12" cy="12" r="10"/>
                            <path d="M8 15h8"/>
                            <path d="M9 9h.01"/>
                            <path d="M15 9h.01"/>
                          </svg>
                          この投稿に興味がない
                        </button>
                      )}
                      {onMute && !isOwnPost && (
                        <button onClick={handleMute} className="w-full px-4 py-2 text-left text-sm text-red-500 hover:bg-[var(--bg-secondary)] flex items-center gap-2">
                          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <circle cx="12" cy="12" r="10"/>
                            <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
                          </svg>
                          ミュート
                        </button>
                      )}
                      {isOwnPost && onDelete && (
                        <button onClick={handleDelete} className="w-full px-4 py-2 text-left text-sm text-red-500 hover:bg-[var(--bg-secondary)] flex items-center gap-2">
                          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <polyline points="3 6 5 6 21 6"/>
                            <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/>
                          </svg>
                          削除
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            </div>

            {/* Article badge */}
            <div className="flex items-center gap-1.5 mb-2">
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-[var(--line-green)]/10 text-[var(--line-green)] text-xs font-medium">
                <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                  <line x1="16" y1="13" x2="8" y2="13"/>
                  <line x1="16" y1="17" x2="8" y2="17"/>
                  <polyline points="10 9 9 9 8 9"/>
                </svg>
                長文記事
              </span>
            </div>

            {/* Article card - clickable to open reader */}
            <button
              onClick={() => setShowArticle(true)}
              className="w-full text-left border border-[var(--border-color)] rounded-xl overflow-hidden bg-[var(--bg-secondary)] hover:border-[var(--line-green)]/50 transition-colors"
            >
              {/* Featured image */}
              {image && (
                <div className="w-full h-40 lg:h-48 overflow-hidden">
                  <img
                    src={getImageUrl(image)}
                    alt={title || ''}
                    className="w-full h-full object-cover"
                    loading="lazy"
                    referrerPolicy="no-referrer"
                    onError={(e) => {
                      e.target.parentElement.style.display = 'none'
                    }}
                  />
                </div>
              )}

              <div className="p-3 lg:p-4">
                {/* Title */}
                {title && (
                  <h3 className="text-base lg:text-lg font-bold text-[var(--text-primary)] mb-1.5 line-clamp-2">
                    {title}
                  </h3>
                )}

                {/* Summary */}
                <p className="text-sm text-[var(--text-secondary)] line-clamp-3 whitespace-pre-wrap">
                  {displaySummary}
                </p>

                {/* Read more indicator */}
                <span className="inline-flex items-center gap-1 text-[var(--line-green)] text-xs mt-2 font-medium">
                  記事を読む
                  <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="9 18 15 12 9 6"/>
                  </svg>
                </span>
              </div>
            </button>

            {/* Hashtags */}
            {hashtags.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mt-2">
                {hashtags.slice(0, 5).map((tag, i) => (
                  <span
                    key={i}
                    onClick={(e) => { e.stopPropagation(); onHashtagClick?.(tag) }}
                    className="text-xs text-[var(--line-green)] cursor-pointer hover:underline"
                  >
                    #{tag}
                  </span>
                ))}
              </div>
            )}

            {/* Actions */}
            {showActions && (
              <div className="flex items-center justify-between mt-3">
                <div className="flex items-center gap-8">
                  {/* Like (long-press for custom emoji reaction) */}
                  <button
                    onClick={handleLikeClick}
                    onTouchStart={handleLikeLongPressStart}
                    onTouchEnd={handleLikeLongPressEnd}
                    onTouchCancel={handleLikeLongPressEnd}
                    onMouseDown={handleLikeLongPressStart}
                    onMouseUp={handleLikeLongPressEnd}
                    onMouseLeave={handleLikeLongPressEnd}
                    onContextMenu={(e) => e.preventDefault()}
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

                {clientTag && (
                  <span className="text-[10px] text-[var(--text-tertiary)] opacity-60">
                    via {clientTag}
                  </span>
                )}
              </div>
            )}
          </div>
        </div>
      </article>

      {/* Article reader modal (full-screen) */}
      {showArticle && (
        <ArticleReaderModal
          post={post}
          profile={displayProfile}
          title={title}
          image={image}
          publishedAt={publishedAt}
          hashtags={hashtags}
          onClose={() => setShowArticle(false)}
          onHashtagClick={onHashtagClick}
        />
      )}

      {/* Reaction Emoji Picker */}
      {showReactionPicker && (
        <ReactionEmojiPicker
          pubkey={myPubkey}
          onSelect={handleReactionSelect}
          onClose={() => setShowReactionPicker(false)}
        />
      )}
    </>
  )
}
