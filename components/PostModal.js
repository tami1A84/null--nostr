'use client'

import { useState, useRef, useEffect } from 'react'
import { publishEvent, uploadImage, nip19 } from '@/lib/nostr'
import EmojiPicker from './EmojiPicker'

/**
 * Post Modal Component
 *
 * Modal for creating new posts with image upload and emoji support.
 *
 * @param {Object} props
 * @param {string} props.pubkey - Current user's public key
 * @param {Object} [props.replyTo] - Event to reply to (if replying)
 * @param {Object} [props.quotedEvent] - Event to quote (if quoting)
 * @param {Function} props.onClose - Close handler
 * @param {Function} [props.onSuccess] - Callback after successful post
 * @returns {JSX.Element}
 */
// Extract hashtags from content (NIP-01)
function extractHashtags(content) {
  if (!content) return []
  // Match #hashtag pattern, supporting Unicode characters
  const hashtagRegex = /#([^\s#\u3000]+)/g
  const hashtags = []
  let match
  while ((match = hashtagRegex.exec(content)) !== null) {
    // Normalize to lowercase
    const tag = match[1].toLowerCase()
    if (!hashtags.includes(tag)) {
      hashtags.push(tag)
    }
  }
  return hashtags
}

export default function PostModal({ pubkey, replyTo, quotedEvent, onClose, onSuccess }) {
  const [postContent, setPostContent] = useState('')
  const [posting, setPosting] = useState(false)
  const [imageFile, setImageFile] = useState(null)
  const [imagePreview, setImagePreview] = useState(null)
  const [uploadingImage, setUploadingImage] = useState(false)
  const [showEmojiPicker, setShowEmojiPicker] = useState(false)
  const [selectedEmojis, setSelectedEmojis] = useState([])
  const [contentWarning, setContentWarning] = useState('')
  const [showCWInput, setShowCWInput] = useState(false)

  const textareaRef = useRef(null)
  const fileInputRef = useRef(null)

  // Auto-focus textarea
  useEffect(() => {
    textareaRef.current?.focus()
  }, [])

  const handleImageSelect = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return

    // Preview
    const reader = new FileReader()
    reader.onloadend = () => {
      setImagePreview(reader.result)
    }
    reader.readAsDataURL(file)
    setImageFile(file)
  }

  const handleRemoveImage = () => {
    setImageFile(null)
    setImagePreview(null)
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  const handleEmojiSelect = (emoji) => {
    // Check if custom emoji (has shortcode and url)
    if (emoji.shortcode && emoji.url) {
      setPostContent((prev) => prev + `:${emoji.shortcode}:`)
      // Track custom emoji for tags
      setSelectedEmojis((prev) => {
        if (!prev.find((e) => e.shortcode === emoji.shortcode)) {
          return [...prev, emoji]
        }
        return prev
      })
    } else {
      // Standard emoji
      setPostContent((prev) => prev + emoji.native)
    }
    setShowEmojiPicker(false)
  }

  const handlePost = async () => {
    const content = postContent.trim()
    if (!content && !imageFile) return
    if (posting) return

    try {
      setPosting(true)

      let finalContent = content

      // Upload image if selected
      if (imageFile) {
        try {
          setUploadingImage(true)
          const imageUrl = await uploadImage(imageFile)
          if (imageUrl) {
            finalContent = finalContent ? `${finalContent}\n${imageUrl}` : imageUrl
          }
        } catch (e) {
          console.error('Image upload failed:', e)
          alert(`画像のアップロードに失敗しました: ${e.message}`)
          return
        } finally {
          setUploadingImage(false)
        }
      }

      // Add quote reference if quoting
      if (quotedEvent) {
        const nevent = nip19.neventEncode({ id: quotedEvent.id })
        finalContent = `${finalContent}\n\nnostr:${nevent}`
      }

      // Build tags
      const tags = []

      // Reply tags
      if (replyTo) {
        tags.push(['e', replyTo.id, '', 'reply'])
        tags.push(['p', replyTo.pubkey])
      }

      // Quote tags
      if (quotedEvent) {
        tags.push(['q', quotedEvent.id])
        tags.push(['p', quotedEvent.pubkey])
      }

      // Custom emoji tags
      selectedEmojis.forEach((emoji) => {
        tags.push(['emoji', emoji.shortcode, emoji.url])
      })

      // Content warning tag (NIP-36)
      if (contentWarning.trim()) {
        tags.push(['content-warning', contentWarning.trim()])
      }

      // Hashtag tags (NIP-01)
      const hashtags = extractHashtags(finalContent)
      hashtags.forEach((hashtag) => {
        tags.push(['t', hashtag])
      })

      await publishEvent({
        kind: 1,
        content: finalContent,
        tags,
        created_at: Math.floor(Date.now() / 1000),
      })

      setPostContent('')
      setImageFile(null)
      setImagePreview(null)
      setSelectedEmojis([])
      setContentWarning('')
      setShowCWInput(false)
      onClose()
      onSuccess?.()
    } catch (e) {
      console.error('Failed to post:', e)
      alert(`投稿に失敗しました: ${e.message}`)
    } finally {
      setPosting(false)
    }
  }

  const handleBackdropClick = (e) => {
    if (e.target === e.currentTarget) {
      onClose()
    }
  }

  const handleKeyDown = (e) => {
    // Ctrl/Cmd + Enter to post
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      handlePost()
    }
    // Escape to close
    if (e.key === 'Escape') {
      onClose()
    }
  }

  const isValid = postContent.trim() || imageFile
  const isLoading = posting || uploadingImage

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center pt-[10vh] modal-overlay"
      onClick={handleBackdropClick}
      role="dialog"
      aria-modal="true"
      aria-labelledby="post-modal-title"
    >
      <div
        className="w-full max-w-lg mx-4 bg-[var(--bg-primary)] rounded-2xl overflow-hidden animate-scaleIn shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="p-4 border-b border-[var(--border-color)]">
          <div className="flex items-center justify-between">
            <button
              onClick={onClose}
              className="text-[var(--text-tertiary)] action-btn p-1"
              aria-label="閉じる"
            >
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
            <h2 id="post-modal-title" className="text-lg font-bold text-[var(--text-primary)]">
              {replyTo ? '返信' : quotedEvent ? '引用' : '新規投稿'}
            </h2>
            <button
              onClick={handlePost}
              disabled={!isValid || isLoading}
              className="btn-line px-4 py-1.5 text-sm disabled:opacity-50"
              aria-busy={isLoading}
            >
              {isLoading ? '投稿中...' : '投稿'}
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="p-4">
          {/* Content Warning Input (NIP-36) */}
          {showCWInput && (
            <div className="mb-3 pb-3 border-b border-[var(--border-color)]">
              <div className="flex items-center gap-2 mb-1.5">
                <svg className="w-4 h-4 text-orange-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                  <line x1="12" y1="9" x2="12" y2="13"/>
                  <line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
                <span className="text-sm font-medium text-orange-500">コンテンツ警告</span>
              </div>
              <input
                type="text"
                value={contentWarning}
                onChange={(e) => setContentWarning(e.target.value)}
                className="w-full px-3 py-2 text-sm bg-[var(--bg-secondary)] border border-[var(--border-color)] rounded-lg text-[var(--text-primary)] placeholder:text-[var(--text-tertiary)] focus:outline-none focus:border-orange-500"
                placeholder="警告の理由（例: ネタバレ、センシティブ）"
                maxLength={100}
              />
            </div>
          )}

          <textarea
            ref={textareaRef}
            value={postContent}
            onChange={(e) => setPostContent(e.target.value)}
            onKeyDown={handleKeyDown}
            className="w-full h-32 resize-none bg-transparent text-[var(--text-primary)] placeholder:text-[var(--text-tertiary)] focus:outline-none"
            placeholder={replyTo ? '返信を入力...' : 'いまなにしてる？'}
            maxLength={10000}
            aria-label="投稿内容"
          />

          {/* Image preview */}
          {imagePreview && (
            <div className="relative mt-3">
              <img
                src={imagePreview}
                alt="プレビュー"
                className="max-h-48 rounded-lg object-contain"
              />
              <button
                onClick={handleRemoveImage}
                className="absolute top-2 right-2 p-1 bg-black/50 rounded-full text-white hover:bg-black/70"
                aria-label="画像を削除"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>
          )}
        </div>

        {/* Toolbar */}
        <div className="px-4 pb-4 flex items-center gap-3 border-t border-[var(--border-color)] pt-3">
          {/* Image upload */}
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            onChange={handleImageSelect}
            className="hidden"
            id="post-image-input"
          />
          <label
            htmlFor="post-image-input"
            className="action-btn p-2 cursor-pointer"
            aria-label="画像を追加"
          >
            <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
              <circle cx="8.5" cy="8.5" r="1.5" />
              <polyline points="21 15 16 10 5 21" />
            </svg>
          </label>

          {/* Content Warning toggle (NIP-36) */}
          <button
            onClick={() => setShowCWInput(!showCWInput)}
            className={`action-btn p-2 ${showCWInput ? 'text-orange-500' : ''}`}
            aria-label="コンテンツ警告を追加"
            title="コンテンツ警告 (CW)"
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
              aria-label="絵文字を追加"
              aria-expanded={showEmojiPicker}
            >
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="12" cy="12" r="10" />
                <path d="M8 14s1.5 2 4 2 4-2 4-2" />
                <line x1="9" y1="9" x2="9.01" y2="9" />
                <line x1="15" y1="9" x2="15.01" y2="9" />
              </svg>
            </button>
            {showEmojiPicker && (
              <div className="absolute bottom-full left-0 mb-2 z-10">
                <EmojiPicker
                  onSelect={handleEmojiSelect}
                  onClose={() => setShowEmojiPicker(false)}
                  pubkey={pubkey}
                />
              </div>
            )}
          </div>

          {/* Character count */}
          <span className="ml-auto text-xs text-[var(--text-tertiary)]">
            {postContent.length}/10000
          </span>
        </div>
      </div>
    </div>
  )
}
