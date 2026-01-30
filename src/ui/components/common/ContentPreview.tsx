'use client'

import React from 'react'
import type { EmojiTag } from '../../types'

interface ContentPreviewProps {
  content: string | null | undefined
  customEmojis?: EmojiTag[]
  className?: string
}

/**
 * Render content preview with hashtags and custom emojis highlighted
 * Used in post composer to show preview of formatted content
 */
export function ContentPreview({ content, customEmojis = [], className }: ContentPreviewProps) {
  if (!content) return null

  // Build emoji map from selected emojis (format: ['emoji', shortcode, url])
  const emojiMap: Record<string, string> = {}
  customEmojis.forEach(emoji => {
    if (emoji[0] === 'emoji' && emoji[1] && emoji[2]) {
      emojiMap[emoji[1]] = emoji[2]
    }
  })

  // Split by hashtags and custom emoji shortcodes
  const combinedRegex = /(#[^\s#\u3000]+|:[a-zA-Z0-9_]+:)/g
  const parts = content.split(combinedRegex).filter(Boolean)

  return (
    <div className={`text-base text-[var(--text-primary)] whitespace-pre-wrap break-words ${className || ''}`}>
      {parts.map((part, i) => {
        // Check for hashtags
        if (part.startsWith('#') && part.length > 1) {
          return (
            <span key={i} className="text-[var(--line-green)]">
              {part}
            </span>
          )
        }

        // Check for custom emoji shortcodes
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
                  (e.target as HTMLImageElement).style.display = 'none'
                }}
              />
            )
          }
          // Show shortcode as text if no URL found
          return <span key={i} className="text-[var(--text-tertiary)]">{part}</span>
        }

        return <span key={i}>{part}</span>
      })}
    </div>
  )
}

export default ContentPreview
