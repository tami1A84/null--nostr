'use client'

import PostItemOriginal from '@/components/PostItem'

// Cast PostItem to any to avoid TypeScript issues with JavaScript component
const PostItem = PostItemOriginal as any

interface Profile {
  name?: string
  picture?: string
  lud16?: string
  [key: string]: any
}

interface Post {
  id: string
  pubkey: string
  content: string
  created_at: number
  tags: string[][]
  _isRepost?: boolean
  _repostedBy?: string
  _repostTime?: number
  _repostId?: string
}

interface TimelineListProps {
  posts: Post[]
  profiles: Record<string, Profile>
  reactions: Record<string, number>
  userReactions: Set<string>
  userReposts: Set<string>
  userReactionIds: Record<string, string>
  userRepostIds: Record<string, string>
  birdwatchLabels: Record<string, any[]>
  mutedPubkeys: Set<string>
  zapAnimating: string | null
  likeAnimating: string | null
  pubkey: string | null
  showNotInterested?: boolean
  onLike: (event: Post) => void
  onUnlike: (event: Post, reactionEventId: string) => void
  onRepost: (event: Post) => void
  onUnrepost: (event: Post, repostEventId: string) => void
  onZap: (event: Post) => void
  onZapLongPress: (event: Post) => void
  onZapLongPressEnd: () => void
  onAvatarClick: (pubkey: string, profile?: Profile) => void
  onHashtagClick: (hashtag: string) => void
  onMute: (pubkey: string) => void
  onDelete: (eventId: string) => void
  onReport?: (reportData: any) => void
  onBirdwatch?: (data: any) => void
  onBirdwatchRate?: (labelEventId: string, rating: string) => void
  onNotInterested?: (eventId: string, authorPubkey: string) => void
}

export default function TimelineList({
  posts,
  profiles,
  reactions,
  userReactions,
  userReposts,
  userReactionIds,
  userRepostIds,
  birdwatchLabels,
  mutedPubkeys,
  zapAnimating,
  likeAnimating,
  pubkey,
  showNotInterested = false,
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
}: TimelineListProps) {
  const filteredPosts = posts.filter(post => !mutedPubkeys.has(post.pubkey))

  return (
    <div className="divide-y divide-[var(--border-color)]">
      {filteredPosts.map((post, index) => {
        const profile = profiles[post.pubkey]
        const likeCount = reactions[post.id] || 0
        const hasLiked = userReactions.has(post.id)
        const hasReposted = userReposts.has(post.id)
        const isZapping = zapAnimating === post.id
        const isLiking = likeAnimating === post.id

        return (
          <div
            key={post._repostId || post.id}
            className="animate-fadeIn"
            style={{ animationDelay: `${Math.min(index * 30, 300)}ms` }}
          >
            <PostItem
              post={post}
              profile={profile}
              profiles={profiles}
              likeCount={likeCount}
              hasLiked={hasLiked}
              hasReposted={hasReposted}
              myReactionId={userReactionIds[post.id] || null}
              myRepostId={userRepostIds[post.id] || null}
              isLiking={isLiking}
              isZapping={isZapping}
              onLike={onLike}
              onUnlike={onUnlike}
              onRepost={onRepost}
              onUnrepost={onUnrepost}
              onZap={onZap}
              onZapLongPress={onZapLongPress}
              onZapLongPressEnd={onZapLongPressEnd}
              onAvatarClick={onAvatarClick}
              onHashtagClick={onHashtagClick}
              onMute={onMute}
              onDelete={onDelete}
              onReport={onReport}
              onBirdwatch={onBirdwatch}
              onBirdwatchRate={onBirdwatchRate}
              onNotInterested={onNotInterested}
              birdwatchNotes={birdwatchLabels[post.id] || []}
              myPubkey={pubkey}
              isOwnPost={post.pubkey === pubkey}
              isRepost={post._isRepost}
              repostedBy={post._repostedBy ? profiles[post._repostedBy] || { pubkey: post._repostedBy } : null}
              showNotInterested={showNotInterested}
            />
          </div>
        )
      })}
    </div>
  )
}
