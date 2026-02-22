'use client'

import { useState, useEffect, useRef, useCallback } from 'react'
import type { Post, Profile, TimelineMode, ReactionCounts } from '../types'

interface UseTimelineOptions {
  pubkey: string | null
  initialMode?: TimelineMode
}

interface UseTimelineReturn {
  // State
  posts: Post[]
  globalPosts: Post[]
  followingPosts: Post[]
  profiles: Record<string, Profile>
  loading: boolean
  loadError: boolean
  loadingMore: boolean
  timelineMode: TimelineMode
  followList: string[]
  followListLoading: boolean
  mutedPubkeys: Set<string>

  // Reactions
  reactions: ReactionCounts
  userReactions: Set<string>
  userReposts: Set<string>
  userReactionIds: Record<string, string>
  userRepostIds: Record<string, string>

  // Actions
  setTimelineMode: (mode: TimelineMode) => void
  loadTimeline: () => Promise<void>
  loadFollowingTimeline: () => Promise<void>
  setProfiles: React.Dispatch<React.SetStateAction<Record<string, Profile>>>
  setMutedPubkeys: React.Dispatch<React.SetStateAction<Set<string>>>
  setPosts: React.Dispatch<React.SetStateAction<Post[]>>

  // Handlers
  handleLike: (event: Post) => Promise<void>
  handleUnlike: (event: Post, reactionEventId: string) => Promise<void>
  handleRepost: (event: Post) => Promise<void>
  handleUnrepost: (event: Post, repostEventId: string) => Promise<void>
  handleDelete: (eventId: string) => Promise<void>
  handleModeChange: (mode: TimelineMode, scrollContainerRef?: React.RefObject<HTMLElement>) => void
}

/**
 * Custom hook for timeline functionality
 * Manages timeline state, loading, and user interactions
 */
export function useTimeline({ pubkey, initialMode = 'global' }: UseTimelineOptions): UseTimelineReturn {
  // Separate state for each timeline mode
  const [globalPosts, setGlobalPosts] = useState<Post[]>([])
  const [followingPosts, setFollowingPosts] = useState<Post[]>([])
  const [profiles, setProfiles] = useState<Record<string, Profile>>({})
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)

  // Reactions state
  const [reactions, setReactions] = useState<ReactionCounts>({})
  const [userReactions, setUserReactions] = useState<Set<string>>(new Set())
  const [userReposts, setUserReposts] = useState<Set<string>>(new Set())
  const [userReactionIds, setUserReactionIds] = useState<Record<string, string>>({})
  const [userRepostIds, setUserRepostIds] = useState<Record<string, string>>({})

  // Follow state
  const [timelineMode, setTimelineMode] = useState<TimelineMode>(initialMode)
  const [followList, setFollowList] = useState<string[]>([])
  const [followListLoading, setFollowListLoading] = useState(false)
  const [followingPrefetched, setFollowingPrefetched] = useState(false)
  const [mutedPubkeys, setMutedPubkeys] = useState<Set<string>>(new Set())

  // Refs
  const initialLoadDone = useRef(false)
  const profilesRef = useRef(profiles)
  profilesRef.current = profiles
  const globalScrollRef = useRef(0)
  const followingScrollRef = useRef(0)

  // Current posts based on mode
  const posts = timelineMode === 'global' ? globalPosts : followingPosts
  const setPosts = timelineMode === 'global' ? setGlobalPosts : setFollowingPosts

  // Load follow list
  const loadFollowList = useCallback(async () => {
    if (!pubkey) return

    setFollowListLoading(true)
    try {
      const { fetchFollowListCached } = await import('@/lib/nostr')
      const follows = await fetchFollowListCached(pubkey, false)
      if (follows && follows.length >= 0) {
        setFollowList(follows)
      }
    } catch (e) {
      console.error('Failed to load follow list:', e)
      setFollowList([])
    } finally {
      setFollowListLoading(false)
    }
  }, [pubkey])

  // Load mute list
  const loadMuteList = useCallback(async () => {
    if (!pubkey) return

    try {
      const { fetchMuteListCached } = await import('@/lib/nostr')
      const muteList = await fetchMuteListCached(pubkey, false)
      if (muteList && muteList.pubkeys) {
        setMutedPubkeys(new Set(muteList.pubkeys))
      }
    } catch (e) {
      console.error('Failed to load mute list:', e)
      setMutedPubkeys(new Set())
    }
  }, [pubkey])

  // Recommended timeline load (global timeline)
  const loadRecommendedTimeline = useCallback(async () => {
    setLoading(true)
    setLoadError(false)

    try {
      const { fetchEvents, getReadRelays, fetchProfilesBatch, getAllCachedProfiles } = await import('@/lib/nostr')
      const { getRecommendedPosts, fetchEngagementData, fetchFollowListsBatch, extract2ndDegreeNetwork } = await import('@/lib/recommendation')
      const { fetchEventsWithOutboxModel } = await import('@/lib/outbox')
      const { NOSTR_KINDS } = await import('@/lib/constants')

      const readRelays = getReadRelays()
      const threeHoursAgo = Math.floor(Date.now() / 1000) - 10800 // 3 hours for candidate posts

      // Load cached profiles
      const cachedProfiles = getAllCachedProfiles() as Record<string, Profile>
      if (Object.keys(cachedProfiles).length > 0) {
        setProfiles(cachedProfiles)
      }

      // Step 1: Fetch base notes and reposts
      const [notes, reposts] = await Promise.all([
        fetchEvents({ kinds: [1, NOSTR_KINDS.LONG_FORM], since: threeHoursAgo, limit: 150 }, readRelays),
        fetchEvents({ kinds: [6], since: threeHoursAgo, limit: 50 }, readRelays)
      ])

      // Parse reposts
      const repostData: any[] = []
      reposts.forEach((repost: any) => {
        try {
          if (repost.content) {
            const originalEvent = JSON.parse(repost.content)
            repostData.push({
              ...originalEvent,
              _repostedBy: repost.pubkey,
              _repostTime: repost.created_at,
              _isRepost: true,
              _repostId: repost.id
            })
          }
        } catch (e) {
          // Skip invalid
        }
      })

      let allPosts = [...notes, ...repostData]

      // Step 2: Build 2nd-degree network and fetch their posts
      let secondDegreeFollows = new Set<string>()
      if (followList.length > 0) {
        try {
          const sampleFollows = followList.slice(0, 30)
          const followsOfFollows = await fetchFollowListsBatch(sampleFollows, readRelays)
          secondDegreeFollows = extract2ndDegreeNetwork(followList, followsOfFollows)

          if (secondDegreeFollows.size > 0) {
            const secondDegreeArray = Array.from(secondDegreeFollows).slice(0, 50)
            const secondDegreePosts = await fetchEventsWithOutboxModel(
              { kinds: [1, NOSTR_KINDS.LONG_FORM], since: threeHoursAgo, limit: 100 },
              secondDegreeArray,
              { timeout: 10000 }
            )
            allPosts = [...allPosts, ...secondDegreePosts]
          }
        } catch (e) {
          console.warn('Failed to fetch 2nd-degree network:', e)
        }
      }

      // Deduplicate posts
      const postMap = new Map()
      allPosts.forEach(p => {
        if (!postMap.has(p.id)) {
          postMap.set(p.id, p)
        }
      })
      allPosts = Array.from(postMap.values())

      // Step 3: Fetch engagement data for scoring
      const eventIds = allPosts.slice(0, 100).map(p => p.id)
      let engagements = {}
      if (eventIds.length > 0) {
        try {
          engagements = await fetchEngagementData(eventIds, readRelays)
        } catch (e) {
          console.warn('Failed to fetch engagement data:', e)
        }
      }

      // Step 4: Apply recommendation algorithm
      const followSet = new Set(followList)
      const recommendedPosts = getRecommendedPosts(allPosts, {
        followList: followSet,
        secondDegreeFollows,
        mutedPubkeys,
        engagements,
        profiles: profilesRef.current,
        userGeohash: typeof window !== 'undefined' ? localStorage.getItem('user_geohash') : null
      }, 100)

      // Fallback to time-sorted if no recommendations
      const finalPosts = recommendedPosts.length > 0 ? recommendedPosts : allPosts.sort((a, b) => {
        const timeA = a._repostTime || a.created_at
        const timeB = b._repostTime || b.created_at
        return timeB - timeA
      })

      setGlobalPosts(finalPosts as Post[])

      // Step 5: Fetch profiles for the timeline
      const authors = new Set<string>()
      allPosts.forEach(p => {
        authors.add(p.pubkey)
        if (p._repostedBy) authors.add(p._repostedBy)
      })

      if (authors.size > 0) {
        const profileMap = await fetchProfilesBatch(Array.from(authors)) as Record<string, Profile>
        setProfiles(prev => ({ ...prev, ...profileMap }))
      }

      setLoading(false)
      initialLoadDone.current = true
    } catch (e) {
      console.error('Failed to load recommended timeline:', e)
      setLoadError(true)
      setLoading(false)
      initialLoadDone.current = true
    }
  }, [followList, mutedPubkeys])

  // Load following timeline
  const loadFollowingTimeline = useCallback(async () => {
    if (followList.length === 0) {
      setFollowingPosts([])
      return
    }

    setFollowListLoading(true)

    try {
      const { fetchEvents, getReadRelays, fetchProfilesBatch } = await import('@/lib/nostr')
      const readRelays = getReadRelays()
      const oneHourAgo = Math.floor(Date.now() / 1000) - 3600

      const notes: any[] = await fetchEvents(
        { kinds: [1], authors: followList, since: oneHourAgo, limit: 100 },
        readRelays
      )

      const sortedPosts = notes.sort((a: any, b: any) => b.created_at - a.created_at) as Post[]
      setFollowingPosts(sortedPosts)

      // Fetch profiles
      const authors = new Set(sortedPosts.map((p: any) => p.pubkey))
      if (authors.size > 0) {
        const profileMap = await fetchProfilesBatch(Array.from(authors)) as Record<string, Profile>
        setProfiles(prev => ({ ...prev, ...profileMap }))
      }
    } catch (e) {
      console.error('Failed to load following timeline:', e)
    } finally {
      setFollowListLoading(false)
    }
  }, [followList])

  // Main loadTimeline function
  const loadTimeline = useCallback(async () => {
    if (timelineMode === 'following') {
      await loadFollowingTimeline()
    } else {
      await loadRecommendedTimeline()
    }
  }, [timelineMode, loadFollowingTimeline, loadRecommendedTimeline])

  // Mode change handler with scroll position preservation
  const handleModeChange = useCallback((newMode: TimelineMode, scrollContainerRef?: React.RefObject<HTMLElement>) => {
    if (newMode === timelineMode) return

    const container = scrollContainerRef?.current
    const currentScroll = container?.scrollTop || 0

    if (timelineMode === 'global') {
      globalScrollRef.current = currentScroll
    } else {
      followingScrollRef.current = currentScroll
    }

    setTimelineMode(newMode)

    setTimeout(() => {
      const targetScroll = newMode === 'global' ? globalScrollRef.current : followingScrollRef.current
      if (container) {
        container.scrollTop = targetScroll
      }
    }, 0)
  }, [timelineMode])

  // Interaction handlers
  const handleLike = useCallback(async (event: Post) => {
    if (!pubkey || userReactions.has(event.id)) return

    try {
      const { signEventNip07, publishEvent, createEventTemplate } = await import('@/lib/nostr')

      const reactionEvent: any = createEventTemplate(7, '+', [
        ['e', event.id],
        ['p', event.pubkey]
      ])
      reactionEvent.pubkey = pubkey

      const signed: any = await signEventNip07(reactionEvent)
      const success = await publishEvent(signed)

      if (success) {
        setUserReactions(prev => new Set([...prev, event.id]))
        setUserReactionIds(prev => ({ ...prev, [event.id]: signed.id }))
        setReactions(prev => ({
          ...prev,
          [event.id]: (prev[event.id] || 0) + 1
        }))
      }
    } catch (e) {
      console.error('Failed to like:', e)
      throw e
    }
  }, [pubkey, userReactions])

  const handleUnlike = useCallback(async (event: Post, reactionEventId: string) => {
    if (!pubkey || !userReactions.has(event.id)) return

    try {
      const { unlikeEvent } = await import('@/lib/nostr')
      const result = await unlikeEvent(reactionEventId)

      if (result.success) {
        setUserReactions(prev => {
          const newSet = new Set(prev)
          newSet.delete(event.id)
          return newSet
        })
        setUserReactionIds(prev => {
          const newIds = { ...prev }
          delete newIds[event.id]
          return newIds
        })
        setReactions(prev => ({
          ...prev,
          [event.id]: Math.max(0, (prev[event.id] || 1) - 1)
        }))
      }
    } catch (e) {
      console.error('Failed to unlike:', e)
    }
  }, [pubkey, userReactions])

  const handleRepost = useCallback(async (event: Post) => {
    if (!pubkey || userReposts.has(event.id)) return

    try {
      const { signEventNip07, publishEvent, createEventTemplate } = await import('@/lib/nostr')

      const repostEvent: any = createEventTemplate(6, JSON.stringify(event), [
        ['e', event.id, '', 'mention'],
        ['p', event.pubkey]
      ])
      repostEvent.pubkey = pubkey

      const signed: any = await signEventNip07(repostEvent)
      const success = await publishEvent(signed)

      if (success) {
        setUserReposts(prev => new Set([...prev, event.id]))
        setUserRepostIds(prev => ({ ...prev, [event.id]: signed.id }))
      }
    } catch (e) {
      console.error('Failed to repost:', e)
      throw e
    }
  }, [pubkey, userReposts])

  const handleUnrepost = useCallback(async (event: Post, repostEventId: string) => {
    if (!pubkey || !userReposts.has(event.id)) return

    try {
      const { unrepostEvent } = await import('@/lib/nostr')
      const result = await unrepostEvent(repostEventId)

      if (result.success) {
        setUserReposts(prev => {
          const newSet = new Set(prev)
          newSet.delete(event.id)
          return newSet
        })
        setUserRepostIds(prev => {
          const newIds = { ...prev }
          delete newIds[event.id]
          return newIds
        })
      }
    } catch (e) {
      console.error('Failed to unrepost:', e)
    }
  }, [pubkey, userReposts])

  const handleDelete = useCallback(async (eventId: string) => {
    try {
      const { deleteEvent } = await import('@/lib/nostr')
      const result = await deleteEvent(eventId)
      if (result.success) {
        setPosts((prev: Post[]) => prev.filter((p: Post) => p.id !== eventId && p._repostId !== eventId))
      }
    } catch (e) {
      console.error('Failed to delete:', e)
      throw e
    }
  }, [setPosts])

  // Effects
  useEffect(() => {
    loadRecommendedTimeline()
  }, [loadRecommendedTimeline])

  useEffect(() => {
    if (pubkey) {
      loadMuteList()
      loadFollowList()
    }
  }, [pubkey, loadMuteList, loadFollowList])

  // Prefetch following timeline
  useEffect(() => {
    if (followList.length > 0 && !followingPrefetched) {
      loadFollowingTimeline().then(() => setFollowingPrefetched(true))
    }
  }, [followList, followingPrefetched, loadFollowingTimeline])

  return {
    // State
    posts,
    globalPosts,
    followingPosts,
    profiles,
    loading,
    loadError,
    loadingMore,
    timelineMode,
    followList,
    followListLoading,
    mutedPubkeys,

    // Reactions
    reactions,
    userReactions,
    userReposts,
    userReactionIds,
    userRepostIds,

    // Actions
    setTimelineMode,
    loadTimeline,
    loadFollowingTimeline,
    setProfiles,
    setMutedPubkeys,
    setPosts,

    // Handlers
    handleLike,
    handleUnlike,
    handleRepost,
    handleUnrepost,
    handleDelete,
    handleModeChange
  }
}

export default useTimeline
