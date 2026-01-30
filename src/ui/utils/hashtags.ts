/**
 * Hashtag extraction utility (NIP-01)
 * Extracts hashtags from content for tagging in Nostr events
 */

/**
 * Extract hashtags from content
 * @param content - Text content to extract hashtags from
 * @returns Array of unique lowercase hashtags (without # prefix)
 */
export function extractHashtags(content: string | null | undefined): string[] {
  if (!content) return []

  // Match hashtags including Japanese characters
  const hashtagRegex = /#([^\s#\u3000]+)/g
  const hashtags: string[] = []
  let match

  while ((match = hashtagRegex.exec(content)) !== null) {
    const tag = match[1].toLowerCase()
    if (!hashtags.includes(tag)) {
      hashtags.push(tag)
    }
  }

  return hashtags
}

/**
 * Convert hashtags to NIP-01 tags format
 * @param hashtags - Array of hashtag strings
 * @returns Array of ['t', hashtag] tag arrays
 */
export function hashtagsToTags(hashtags: string[]): string[][] {
  return hashtags.map(tag => ['t', tag])
}

/**
 * Extract hashtags and convert to tags in one step
 * @param content - Text content
 * @returns Array of ['t', hashtag] tag arrays
 */
export function extractHashtagTags(content: string | null | undefined): string[][] {
  return hashtagsToTags(extractHashtags(content))
}
