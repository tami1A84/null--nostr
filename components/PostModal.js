'use client'

import { useState, useRef, useEffect } from 'react'
import { publishEvent, uploadImage, nip19 } from '@/lib/nostr'
import EmojiPicker from './EmojiPicker'

// Extract hashtags from content (NIP-01)
function extractHashtags(content) {
  if (!content) return []
  const hashtagRegex = /#([^\s#\u3000]+)/g
  const hashtags = []
  let match
  while ((match = hashtagRegex.exec(content)) !== null) {
    const tag = match[1].toLowerCase()
    if (!hashtags.includes(tag)) {
      hashtags.push(tag)
    }
  }
  return hashtags
}

// Render content preview with hashtags and custom emojis highlighted
function ContentPreview({ content, customEmojis = [] }) {
  if (!content) return null

  const emojiMap = {}
  customEmojis.forEach(emoji => {
    if (emoji.shortcode && emoji.url) {
      emojiMap[emoji.shortcode] = emoji.url
    }
  })

  const combinedRegex = /(#[^\s#\u3000]+|:[a-zA-Z0-9_]+:)/g
  const parts = content.split(combinedRegex).filter(Boolean)

  return (
    <div className="text-sm text-[var(--text-primary)] whitespace-pre-wrap break-words">
      {parts.map((part, i) => {
        if (part.startsWith('#') && part.length > 1) {
          return <span key={i} className="text-[var(--line-green)]">{part}</span>
        }
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
                onError={(e) => { e.target.style.display = 'none' }}
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

// Maximum number of images allowed
const MAX_IMAGES = 3

export default function PostModal({ pubkey, replyTo, quotedEvent, onClose, onSuccess }) {
  const [postContent, setPostContent] = useState('')
  const [posting, setPosting] = useState(false)
  const [imageFiles, setImageFiles] = useState([])
  const [imagePreviews, setImagePreviews] = useState([])
  const [uploadingImage, setUploadingImage] = useState(false)
  const [uploadProgress, setUploadProgress] = useState('')
  const [showEmojiPicker, setShowEmojiPicker] = useState(false)
  const [selectedEmojis, setSelectedEmojis] = useState([])
  const [contentWarning, setContentWarning] = useState('')
  const [showCWInput, setShowCWInput] = useState(false)

  const textareaRef = useRef(null)
  const fileInputRef = useRef(null)
  const addFileInputRef = useRef(null)

  useEffect(() => {
    textareaRef.current?.focus()
  }, [])

  // Handle image selection - supports multiple files
  const handleImageSelect = (e) => {
    const files = Array.from(e.target.files || [])
    if (files.length === 0) return

    const remainingSlots = MAX_IMAGES - imageFiles.length
    const filesToAdd = files.slice(0, remainingSlots)

    if (filesToAdd.length === 0) {
      alert(`最大${MAX_IMAGES}枚まで画像を追加できます`)
      return
    }

    // Create previews for each file
    filesToAdd.forEach(file => {
      const reader = new FileReader()
      reader.onloadend = () => {
        setImagePreviews(prev => [...prev, reader.result])
      }
      reader.readAsDataURL(file)
    })

    setImageFiles(prev => [...prev, ...filesToAdd])

    // Reset file inputs
    if (fileInputRef.current) fileInputRef.current.value = ''
    if (addFileInputRef.current) addFileInputRef.current.value = ''
  }

  // Remove image at specific index
  const handleRemoveImage = (index) => {
    setImageFiles(prev => prev.filter((_, i) => i !== index))
    setImagePreviews(prev => prev.filter((_, i) => i !== index))
  }

  const handleEmojiSelect = (emoji) => {
    if (emoji.shortcode && emoji.url) {
      setPostContent(prev => prev + `:${emoji.shortcode}:`)
      setSelectedEmojis(prev => {
        if (!prev.find(e => e.shortcode === emoji.shortcode)) {
          return [...prev, emoji]
        }
        return prev
      })
    } else {
      setPostContent(prev => prev + emoji.native)
    }
    setShowEmojiPicker(false)
  }

  // Upload image with retry logic
  const uploadImageWithRetry = async (file, retries = 3) => {
    for (let i = 0; i < retries; i++) {
      try {
        const url = await uploadImage(file)
        if (url) return url
      } catch (e) {
        console.error(`Upload attempt ${i + 1} failed:`, e)
        if (i === retries - 1) throw e
        // Wait before retry (exponential backoff)
        await new Promise(resolve => setTimeout(resolve, 1000 * (i + 1)))
      }
    }
    return null
  }

  const handlePost = async () => {
    const content = postContent.trim()
    if (!content && imageFiles.length === 0) return
    if (posting) return

    try {
      setPosting(true)
      let finalContent = content

      // Upload images if selected
      if (imageFiles.length > 0) {
        try {
          setUploadingImage(true)
          const uploadedUrls = []

          for (let i = 0; i < imageFiles.length; i++) {
            setUploadProgress(`画像をアップロード中... (${i + 1}/${imageFiles.length})`)
            const imageUrl = await uploadImageWithRetry(imageFiles[i])
            if (imageUrl) {
              uploadedUrls.push(imageUrl)
            }
          }

          if (uploadedUrls.length > 0) {
            const imageUrlsStr = uploadedUrls.join('\n')
            finalContent = finalContent ? `${finalContent}\n${imageUrlsStr}` : imageUrlsStr
          }
        } catch (e) {
          console.error('Image upload failed:', e)
          alert(`画像のアップロードに失敗しました: ${e.message}`)
          return
        } finally {
          setUploadingImage(false)
          setUploadProgress('')
        }
      }

      // Add quote reference if quoting
      if (quotedEvent) {
        const nevent = nip19.neventEncode({ id: quotedEvent.id })
        finalContent = `${finalContent}\n\nnostr:${nevent}`
      }

      // Build tags
      const tags = []

      if (replyTo) {
        tags.push(['e', replyTo.id, '', 'reply'])
        tags.push(['p', replyTo.pubkey])
      }

      if (quotedEvent) {
        tags.push(['q', quotedEvent.id])
        tags.push(['p', quotedEvent.pubkey])
      }

      selectedEmojis.forEach(emoji => {
        tags.push(['emoji', emoji.shortcode, emoji.url])
      })

      if (contentWarning.trim()) {
        tags.push(['content-warning', contentWarning.trim()])
      }

      const hashtags = extractHashtags(finalContent)
      hashtags.forEach(hashtag => {
        tags.push(['t', hashtag])
      })

      await publishEvent({
        kind: 1,
        content: finalContent,
        tags,
        created_at: Math.floor(Date.now() / 1000),
      })

      setPostContent('')
      setImageFiles([])
      setImagePreviews([])
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
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      handlePost()
    }
    if (e.key === 'Escape') {
      onClose()
    }
  }

  const isValid = postContent.trim() || imageFiles.length > 0
  const isLoading = posting || uploadingImage
  const canAddMoreImages = imageFiles.length < MAX_IMAGES

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center pt-[10vh] modal-overlay"
      onClick={handleBackdropClick}
      role="dialog"
      aria-modal="true"
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
            <h2 className="text-lg font-bold text-[var(--text-primary)]">
              {replyTo ? '返信' : quotedEvent ? '引用' : '新規投稿'}
            </h2>
            <button
              onClick={handlePost}
              disabled={!isValid || isLoading}
              className="btn-line px-4 py-1.5 text-sm disabled:opacity-50"
            >
              {isLoading ? (uploadProgress || '投稿中...') : '投稿'}
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="p-4">
          {/* Content Warning Input */}
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

          {/* Textarea */}
          <div className="relative">
            <textarea
              ref={textareaRef}
              value={postContent}
              onChange={(e) => setPostContent(e.target.value)}
              onKeyDown={handleKeyDown}
              spellCheck={false}
              className={`w-full h-32 resize-none bg-transparent placeholder:text-[var(--text-tertiary)] focus:outline-none ${
                postContent && (postContent.includes('#') || selectedEmojis.length > 0)
                  ? 'text-transparent caret-[var(--text-primary)] absolute inset-0 z-10'
                  : 'text-[var(--text-primary)] relative'
              }`}
              placeholder={replyTo ? '返信を入力...' : 'いまなにしてる？'}
              maxLength={10000}
            />
            {postContent && (postContent.includes('#') || selectedEmojis.length > 0) && (
              <div className="w-full h-32 overflow-y-auto pointer-events-none">
                <ContentPreview content={postContent} customEmojis={selectedEmojis} />
              </div>
            )}
          </div>

          {/* Image Previews - Grid layout like TalkTab */}
          {imagePreviews.length > 0 && (
            <div className="mt-3">
              <div className="flex flex-wrap gap-2">
                {imagePreviews.map((preview, index) => (
                  <div key={index} className="relative">
                    <img
                      src={preview}
                      alt={`プレビュー ${index + 1}`}
                      className="h-20 w-20 rounded-lg object-cover"
                    />
                    <button
                      onClick={() => handleRemoveImage(index)}
                      className="absolute -top-1 -right-1 p-1 bg-black/60 rounded-full text-white hover:bg-black/80 transition-colors"
                      aria-label="画像を削除"
                    >
                      <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <line x1="18" y1="6" x2="6" y2="18" />
                        <line x1="6" y1="6" x2="18" y2="18" />
                      </svg>
                    </button>
                  </div>
                ))}
                {/* Add more button */}
                {canAddMoreImages && (
                  <label
                    htmlFor="post-image-add"
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
                ref={addFileInputRef}
                type="file"
                accept="image/*"
                multiple
                onChange={handleImageSelect}
                className="hidden"
                id="post-image-add"
              />
            </div>
          )}
        </div>

        {/* Toolbar */}
        <div className="px-4 pb-4 flex items-center gap-3 border-t border-[var(--border-color)] pt-3">
          {/* Image upload button */}
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            multiple
            onChange={handleImageSelect}
            className="hidden"
            id="post-image-input"
          />
          <label
            htmlFor="post-image-input"
            className={`action-btn p-2 cursor-pointer relative ${!canAddMoreImages ? 'opacity-50 pointer-events-none' : ''}`}
            aria-label="画像を追加"
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

          <span className="ml-auto text-xs text-[var(--text-tertiary)]">
            {postContent.length}/10000
          </span>
        </div>
      </div>
    </div>
  )
}
