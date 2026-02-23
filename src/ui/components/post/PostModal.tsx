'use client'

import React, { useState, useRef } from 'react'
import { ContentPreview } from '../common/ContentPreview'
import type { EmojiTag, CustomEmoji } from '../../types'
import { useSTT } from '@/hooks/useSTT'

interface PostModalProps {
  pubkey: string
  onClose: () => void
  onPost: (content: string, emojiTags: EmojiTag[], contentWarning: string, imageUrls: string[]) => Promise<void>
  posting?: boolean
  uploadProgress?: string
  EmojiPickerComponent?: React.ComponentType<{
    pubkey: string
    onSelect: (emoji: CustomEmoji) => void
    onClose: () => void
  }>
}

const MAX_IMAGES = 3

/**
 * Modal for composing new posts
 */
export function PostModal({
  pubkey,
  onClose,
  onPost,
  posting = false,
  uploadProgress = '',
  EmojiPickerComponent
}: PostModalProps) {
  const [content, setContent] = useState('')
  const [imageFiles, setImageFiles] = useState<File[]>([])
  const [imagePreviews, setImagePreviews] = useState<string[]>([])
  const [uploadingImage, setUploadingImage] = useState(false)
  const [showEmojiPicker, setShowEmojiPicker] = useState(false)
  const [emojiTags, setEmojiTags] = useState<EmojiTag[]>([])
  const [contentWarning, setContentWarning] = useState('')
  const [showCWInput, setShowCWInput] = useState(false)

  // Speech to Text
  const handleTranscript = React.useCallback((text: string) => {
    setContent(prev => prev ? prev + ' ' + text : text)
  }, [])
  const { isRecording: isSTTActive, toggleRecording: toggleSTT } = useSTT(handleTranscript)

  const imageInputRef = useRef<HTMLInputElement>(null)
  const imageAddRef = useRef<HTMLInputElement>(null)

  const handleImageSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || [])
    if (files.length === 0) return

    const remainingSlots = MAX_IMAGES - imageFiles.length
    const filesToAdd = files.slice(0, remainingSlots)

    if (filesToAdd.length === 0) {
      alert(`最大${MAX_IMAGES}枚まで画像を追加できます`)
      return
    }

    filesToAdd.forEach(file => {
      const reader = new FileReader()
      reader.onloadend = () => {
        setImagePreviews(prev => [...prev, reader.result as string])
      }
      reader.readAsDataURL(file)
    })

    setImageFiles(prev => [...prev, ...filesToAdd])

    if (imageInputRef.current) imageInputRef.current.value = ''
    if (imageAddRef.current) imageAddRef.current.value = ''
  }

  const handleRemoveImage = (index: number) => {
    setImageFiles(prev => prev.filter((_, i) => i !== index))
    setImagePreviews(prev => prev.filter((_, i) => i !== index))
  }

  const handleEmojiSelect = (emoji: CustomEmoji) => {
    setContent(prev => prev + `:${emoji.shortcode}:`)
    if (!emojiTags.some(t => t[1] === emoji.shortcode)) {
      setEmojiTags(prev => [...prev, ['emoji', emoji.shortcode, emoji.url]])
    }
    setShowEmojiPicker(false)
  }

  const handleSubmit = async () => {
    if ((!content.trim() && imageFiles.length === 0) || !pubkey) return

    // Upload images and get URLs
    let imageUrls: string[] = []
    if (imageFiles.length > 0) {
      try {
        setUploadingImage(true)
        const { uploadImagesInParallel } = await import('@/lib/imageUtils')
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const result = await (uploadImagesInParallel as any)(imageFiles, {})
        imageUrls = result?.urls || []
      } catch (e) {
        console.error('Image upload failed:', e)
        alert('画像のアップロードに失敗しました')
        setUploadingImage(false)
        return
      } finally {
        setUploadingImage(false)
      }
    }

    await onPost(content, emojiTags, contentWarning, imageUrls)
  }

  const handleClose = () => {
    setImageFiles([])
    setImagePreviews([])
    onClose()
  }

  const showPreview = content && (content.includes('#') || emojiTags.length > 0)
  const isSubmitDisabled = posting || uploadingImage || (!content.trim() && imageFiles.length === 0)

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center modal-overlay"
      onClick={handleClose}
    >
      <div
        className="w-full h-full sm:h-auto sm:max-w-lg bg-[var(--bg-primary)] sm:rounded-2xl flex flex-col overflow-hidden animate-scaleIn"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)] flex-shrink-0">
          <button onClick={handleClose} className="text-[var(--text-secondary)] action-btn whitespace-nowrap flex-shrink-0">
            キャンセル
          </button>
          <span className="font-semibold text-[var(--text-primary)] whitespace-nowrap flex-shrink-0">新規投稿</span>
          <button
            onClick={handleSubmit}
            disabled={isSubmitDisabled}
            className="btn-line text-sm py-1.5 px-4 disabled:opacity-50 whitespace-nowrap flex-shrink-0"
          >
            {uploadingImage ? uploadProgress || 'アップロード中...' : posting ? '投稿中...' : '投稿'}
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 p-4 pb-20 sm:pb-4 flex flex-col overflow-y-auto">
          {/* Content Warning Input (NIP-36) */}
          {showCWInput && (
            <div className="mb-3 pb-3 border-b border-[var(--border-color)]">
              <div className="flex items-center gap-2 mb-1.5">
                <WarningIcon className="w-4 h-4 text-orange-500" />
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

          {/* Textarea with preview */}
          <div className="relative min-h-[120px] sm:min-h-[150px]">
            <textarea
              value={content}
              onChange={(e) => setContent(e.target.value)}
              spellCheck={false}
              className={`w-full min-h-[120px] sm:min-h-[150px] bg-transparent resize-none placeholder-[var(--text-tertiary)] outline-none text-base ${
                showPreview
                  ? 'text-transparent caret-[var(--text-primary)] absolute inset-0 z-10'
                  : 'text-[var(--text-primary)] relative'
              }`}
              placeholder="いまどうしてる？"
              autoFocus
            />
            {showPreview && (
              <div className="w-full min-h-[120px] sm:min-h-[150px] pointer-events-none">
                <ContentPreview content={content} customEmojis={emojiTags} />
              </div>
            )}
          </div>

          {/* Image Previews */}
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
                      <CloseIcon className="w-3 h-3" />
                    </button>
                  </div>
                ))}
                {imageFiles.length < MAX_IMAGES && (
                  <label
                    htmlFor="post-image-add"
                    className="h-20 w-20 rounded-lg border-2 border-dashed border-[var(--border-color)] flex items-center justify-center cursor-pointer hover:border-[var(--line-green)] transition-colors"
                  >
                    <PlusIcon className="w-6 h-6 text-[var(--text-tertiary)]" />
                  </label>
                )}
              </div>
              <input
                ref={imageAddRef}
                type="file"
                accept="image/*"
                multiple
                onChange={handleImageSelect}
                className="hidden"
                id="post-image-add"
              />
            </div>
          )}

          {/* Action Buttons */}
          <div className="mt-3 pt-3 border-t border-[var(--border-color)] flex-shrink-0">
            <input
              ref={imageInputRef}
              type="file"
              accept="image/*"
              multiple
              className="hidden"
              onChange={handleImageSelect}
              id="post-image-input"
            />
            <div className="flex items-center gap-4">
              {/* Image Button */}
              <label
                htmlFor="post-image-input"
                className={`flex items-center gap-2 text-[var(--line-green)] text-sm cursor-pointer ${
                  imageFiles.length >= MAX_IMAGES ? 'opacity-50 pointer-events-none' : ''
                }`}
              >
                <ImageIcon className="w-5 h-5" />
                画像 {imageFiles.length > 0 && `(${imageFiles.length}/${MAX_IMAGES})`}
              </label>

              {/* Content Warning Button */}
              <button
                onClick={() => setShowCWInput(!showCWInput)}
                className={`flex items-center gap-2 text-sm ${
                  showCWInput ? 'text-orange-500' : 'text-[var(--text-tertiary)]'
                }`}
                title="コンテンツ警告 (CW)"
              >
                <WarningIcon className="w-5 h-5" />
                CW
              </button>

              {/* Emoji Button */}
              {EmojiPickerComponent && (
                <button
                  onClick={() => setShowEmojiPicker(!showEmojiPicker)}
                  className={`flex items-center gap-2 text-sm ${
                    showEmojiPicker ? 'text-[var(--line-green)]' : 'text-[var(--text-tertiary)]'
                  }`}
                >
                  <EmojiIcon className="w-5 h-5" />
                  絵文字
                </button>
              )}

              {/* Microphone button for STT */}
              <button
                onClick={toggleSTT}
                className={`flex items-center gap-2 text-sm ${
                  isSTTActive ? 'text-red-500 animate-pulse' : 'text-[var(--text-tertiary)]'
                }`}
                title="音声入力"
              >
                <MicIcon className="w-5 h-5" />
                音声
              </button>
            </div>

            {/* Emoji Picker */}
            {showEmojiPicker && EmojiPickerComponent && (
              <div className="mt-3">
                <EmojiPickerComponent
                  pubkey={pubkey}
                  onSelect={handleEmojiSelect}
                  onClose={() => setShowEmojiPicker(false)}
                />
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

// Icon components
function CloseIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  )
}

function WarningIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
  )
}

function ImageIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
      <circle cx="8.5" cy="8.5" r="1.5" />
      <polyline points="21 15 16 10 5 21" />
    </svg>
  )
}

function EmojiIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10" />
      <path d="M8 14s1.5 2 4 2 4-2 4-2" />
      <line x1="9" y1="9" x2="9.01" y2="9" />
      <line x1="15" y1="9" x2="15.01" y2="9" />
    </svg>
  )
}

function PlusIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  )
}

function MicIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
      <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
      <line x1="12" y1="19" x2="12" y2="23"/>
      <line x1="8" y1="23" x2="16" y2="23"/>
    </svg>
  )
}

export default PostModal
