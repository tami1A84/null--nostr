'use client'

import { useState, useEffect, useCallback, useRef, useImperativeHandle, forwardRef } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchEvents,
  parseProfile,
  signEventNip07,
  createEventTemplate,
  publishEvent,
  shortenPubkey,
  formatTimestamp,
  hasNip07,
  addToMuteList,
  fetchMuteListCached,
  fetchLightningInvoice,
  copyToClipboard,
  unlikeEvent,
  unrepostEvent,
  deleteEvent,
  fetchFollowListCached,
  fetchProfilesBatch,
  getAllCachedProfiles,
  getReadRelays,
  getWriteRelays,
  uploadImage,
  RELAYS
} from '@/lib/nostr'
import { setCachedMuteList } from '@/lib/cache'
import PostItem from './PostItem'
import UserProfileView from './UserProfileView'
import SearchModal from './SearchModal'
import EmojiPicker from './EmojiPicker'

const TimelineTab = forwardRef(function TimelineTab({ pubkey, onStartDM, scrollContainerRef, onPostPublished, isDesktop = false, isActive = true }, ref) {
  // Separate state for each timeline mode
  const [globalPosts, setGlobalPosts] = useState([])
  const [followingPosts, setFollowingPosts] = useState([])
  const [profiles, setProfiles] = useState({})
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [reactions, setReactions] = useState({})
  const [userReactions, setUserReactions] = useState(new Set())
  const [userReposts, setUserReposts] = useState(new Set())
  const [userReactionIds, setUserReactionIds] = useState({}) // eventId -> reactionEventId
  const [userRepostIds, setUserRepostIds] = useState({}) // eventId -> repostEventId
  const [zapAnimating, setZapAnimating] = useState(null)
  const [likeAnimating, setLikeAnimating] = useState(null)
  const [showPostModal, setShowPostModal] = useState(false)
  const [newPost, setNewPost] = useState('')
  const [postImage, setPostImage] = useState(null)
  const [uploadingPostImage, setUploadingPostImage] = useState(false)
  const [posting, setPosting] = useState(false)
  const [showEmojiPicker, setShowEmojiPicker] = useState(false)
  const [emojiTags, setEmojiTags] = useState([]) // Array of emoji tags for post
  const [viewingProfile, setViewingProfile] = useState(null)
  const [mutedPubkeys, setMutedPubkeys] = useState(new Set())
  const [showZapModal, setShowZapModal] = useState(null)
  const [zapAmount, setZapAmount] = useState('')
  const [zapComment, setZapComment] = useState('')
  const [zapping, setZapping] = useState(false)
  const [showSearch, setShowSearch] = useState(false)
  // Follow timeline state
  const [timelineMode, setTimelineMode] = useState('global') // 'global' or 'following'
  const [followList, setFollowList] = useState([])
  const [followListLoading, setFollowListLoading] = useState(false)
  const [followingPrefetched, setFollowingPrefetched] = useState(false)
  const subRef = useRef(null)
  const longPressTimerRef = useRef(null)
  const postImageInputRef = useRef(null)
  const initialLoadDone = useRef(false)
  // Scroll position refs for each timeline
  const globalScrollRef = useRef(0)
  const followingScrollRef = useRef(0)

  // Get posts for current mode
  const posts = timelineMode === 'global' ? globalPosts : followingPosts
  const setPosts = timelineMode === 'global' ? setGlobalPosts : setFollowingPosts

  // Expose refresh function to parent
  useImperativeHandle(ref, () => ({
    refresh: loadTimeline,
    closeProfile: () => setViewingProfile(null),
    openPostModal: () => setShowPostModal(true),
    closeSearch: () => setShowSearch(false)
  }))

  // Save scroll position when switching modes - use scrollContainerRef from parent
  const handleModeChange = (newMode) => {
    if (newMode === timelineMode) return
    
    // Save current scroll position from container
    const container = scrollContainerRef?.current
    const currentScroll = container?.scrollTop || 0
    
    if (timelineMode === 'global') {
      globalScrollRef.current = currentScroll
    } else {
      followingScrollRef.current = currentScroll
    }
    
    setTimelineMode(newMode)
    
    // Restore scroll position after state update
    setTimeout(() => {
      const targetScroll = newMode === 'global' ? globalScrollRef.current : followingScrollRef.current
      if (container) {
        container.scrollTop = targetScroll
      }
    }, 0)
  }

  // Lock body scroll when modal is open
  useEffect(() => {
    if (showPostModal || viewingProfile || showZapModal || showSearch) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [showPostModal, viewingProfile, showZapModal, showSearch])

  // Close search modal when tab becomes inactive
  useEffect(() => {
    if (!isActive && showSearch) {
      setShowSearch(false)
    }
  }, [isActive, showSearch])

  useEffect(() => {
    // Immediately load cached profiles for instant display
    const cachedProfiles = getAllCachedProfiles()
    if (Object.keys(cachedProfiles).length > 0) {
      setProfiles(cachedProfiles)
    }
    
    // Start loading data
    loadTimelineQuick()
    
    return () => {
      if (subRef.current) {
        subRef.current.close()
      }
    }
  }, [])

  // Load mute list and follow list when pubkey is available
  useEffect(() => {
    if (pubkey) {
      loadMuteList()
      loadFollowList()
    }
  }, [pubkey])

  // Prefetch following timeline when follow list is loaded (for both mobile and desktop)
  useEffect(() => {
    if (followList.length > 0 && !followingPrefetched) {
      prefetchFollowingTimeline()
    }
  }, [followList, followingPrefetched])

  // Reload timeline when mode changes (only if not prefetched)
  useEffect(() => {
    if (!loading && initialLoadDone.current) {
      if (timelineMode === 'following' && followingPosts.length === 0) {
        loadTimeline()
      } else if (timelineMode === 'global' && globalPosts.length === 0) {
        loadTimeline()
      }
    }
  }, [timelineMode])

  const loadFollowList = async () => {
    if (!pubkey) return
    setFollowListLoading(true)
    let retries = 2
    
    while (retries >= 0) {
      try {
        const follows = await fetchFollowListCached(pubkey, retries < 2) // Force refresh on retry
        if (follows && follows.length > 0) {
          setFollowList(follows)
          setFollowListLoading(false)
          return
        } else if (follows && follows.length === 0) {
          // User has no follows, this is valid
          setFollowList([])
          setFollowListLoading(false)
          return
        }
      } catch (e) {
        console.error('Failed to load follow list:', e, 'Retries left:', retries)
      }
      retries--
      if (retries >= 0) {
        await new Promise(r => setTimeout(r, 500))
      }
    }
    // Default to empty array on complete failure
    setFollowList([])
    setFollowListLoading(false)
  }

  const loadMuteList = async () => {
    if (!pubkey) return
    let retries = 2
    
    while (retries >= 0) {
      try {
        const muteList = await fetchMuteListCached(pubkey, retries < 2) // Force refresh on retry
        if (muteList && muteList.pubkeys) {
          setMutedPubkeys(new Set(muteList.pubkeys))
          return
        }
      } catch (e) {
        console.error('Failed to load mute list:', e, 'Retries left:', retries)
      }
      retries--
      if (retries >= 0) {
        await new Promise(r => setTimeout(r, 500))
      }
    }
    // Default to empty set on complete failure
    setMutedPubkeys(new Set())
  }

  // Prefetch following timeline in background while viewing global
  const prefetchFollowingTimeline = async () => {
    if (followList.length === 0) return
    
    const readRelays = getReadRelays()
    const oneHourAgo = Math.floor(Date.now() / 1000) - 3600
    
    try {
      const [notes, reposts] = await Promise.all([
        fetchEvents({ kinds: [1], authors: followList, since: oneHourAgo, limit: 50 }, readRelays),
        fetchEvents({ kinds: [6], authors: followList, since: oneHourAgo, limit: 20 }, readRelays)
      ])

      const repostData = []
      const originalAuthors = new Set()

      for (const repost of reposts) {
        try {
          if (repost.content) {
            const originalEvent = JSON.parse(repost.content)
            originalAuthors.add(originalEvent.pubkey)
            repostData.push({
              ...originalEvent,
              _repostedBy: repost.pubkey,
              _repostTime: repost.created_at,
              _isRepost: true,
              _repostId: repost.id
            })
          }
        } catch (e) {}
      }

      const allPosts = [...notes, ...repostData].sort((a, b) => {
        const timeA = a._repostTime || a.created_at
        const timeB = b._repostTime || b.created_at
        return timeB - timeA
      })

      setFollowingPosts(allPosts)
      setFollowingPrefetched(true)
      
      // Fetch profiles for following timeline
      const authors = new Set()
      allPosts.forEach(p => {
        authors.add(p.pubkey)
        if (p._repostedBy) authors.add(p._repostedBy)
      })
      originalAuthors.forEach(a => authors.add(a))
      
      if (authors.size > 0) {
        const profileMap = await fetchProfilesBatch(Array.from(authors))
        setProfiles(prev => ({ ...prev, ...profileMap }))
      }
    } catch (e) {
      console.error('Failed to prefetch following timeline:', e)
    }
  }

  const handleAvatarClick = (targetPubkey, profile) => {
    if (targetPubkey !== pubkey) {
      setViewingProfile(targetPubkey)
    }
  }

  const handleMute = async (targetPubkey) => {
    if (!pubkey) return
    try {
      await addToMuteList(pubkey, 'pubkey', targetPubkey)
      setMutedPubkeys(prev => new Set([...prev, targetPubkey]))
      // Update cache
      const newMuteList = { pubkeys: [...mutedPubkeys, targetPubkey], eventIds: [], hashtags: [], words: [] }
      setCachedMuteList(pubkey, newMuteList)
    } catch (e) {
      console.error('Failed to mute:', e)
    }
  }

  // Quick initial load - just 20 events for fast display (global timeline)
  const loadTimelineQuick = async () => {
    setLoading(true)
    setLoadError(false)
    const readRelays = getReadRelays()
    const fiveMinutesAgo = Math.floor(Date.now() / 1000) - 300 // 5 minutes for quick load
    
    try {
      // Fetch just 20 notes for instant display
      const notes = await fetchEvents(
        { kinds: [1], since: fiveMinutesAgo, limit: 20 },
        readRelays
      )
      
      // If no notes found and this is first load, expand time range
      if (notes.length === 0) {
        const oneHourAgo = Math.floor(Date.now() / 1000) - 3600
        const expandedNotes = await fetchEvents(
          { kinds: [1], since: oneHourAgo, limit: 30 },
          readRelays
        )
        if (expandedNotes.length === 0) {
          // Still no notes - might be connection issue
          setLoadError(true)
        } else {
          setGlobalPosts(expandedNotes.sort((a, b) => b.created_at - a.created_at))
        }
      } else {
        const sortedPosts = notes.sort((a, b) => b.created_at - a.created_at)
        setGlobalPosts(sortedPosts)
      }
      
      // Get authors and fetch profiles
      const currentPosts = globalPosts.length > 0 ? globalPosts : notes
      const authors = new Set(currentPosts.map(p => p.pubkey))
      
      if (authors.size > 0) {
        // Use batch fetch with caching
        const profileMap = await fetchProfilesBatch(Array.from(authors))
        setProfiles(prev => ({ ...prev, ...profileMap }))
      }
      
      setLoading(false)
      initialLoadDone.current = true
      
      // Now load full timeline in background
      loadTimelineFull()
      
    } catch (e) {
      console.error('Failed to quick load timeline:', e)
      setLoadError(true)
      setLoading(false)
      initialLoadDone.current = true
    }
  }

  // Full timeline load (runs in background after quick load - global timeline only)
  const loadTimelineFull = async () => {
    setLoadingMore(true)
    const readRelays = getReadRelays()
    const oneHourAgo = Math.floor(Date.now() / 1000) - 3600 // 1 hour
    
    try {
      const noteFilter = { kinds: [1], since: oneHourAgo, limit: 100 }
      const repostFilter = { kinds: [6], since: oneHourAgo, limit: 50 }
      
      const [notes, reposts] = await Promise.all([
        fetchEvents(noteFilter, readRelays),
        fetchEvents(repostFilter, readRelays)
      ])

      // Parse reposts
      const repostData = []
      const originalAuthors = new Set()

      for (const repost of reposts) {
        try {
          if (repost.content) {
            const originalEvent = JSON.parse(repost.content)
            originalAuthors.add(originalEvent.pubkey)
            repostData.push({
              ...originalEvent,
              _repostedBy: repost.pubkey,
              _repostTime: repost.created_at,
              _isRepost: true,
              _repostId: repost.id
            })
          }
        } catch (e) {
          // Skip invalid reposts
        }
      }

      const allPosts = [...notes, ...repostData].sort((a, b) => {
        const timeA = a._repostTime || a.created_at
        const timeB = b._repostTime || b.created_at
        return timeB - timeA
      })

      setGlobalPosts(allPosts)
      
      // Get unique authors
      const authors = new Set()
      allPosts.forEach(p => {
        authors.add(p.pubkey)
        if (p._repostedBy) authors.add(p._repostedBy)
      })
      originalAuthors.forEach(a => authors.add(a))
      
      // Batch fetch profiles with caching
      if (authors.size > 0) {
        const profileMap = await fetchProfilesBatch(Array.from(authors))
        setProfiles(prev => ({ ...prev, ...profileMap }))
      }

      // Fetch reactions
      if (pubkey && allPosts.length > 0) {
        const eventIds = allPosts.map(p => p.id)
        const [reactionEvents, myRepostEvents] = await Promise.all([
          fetchEvents({ kinds: [7], '#e': eventIds, limit: 500 }, readRelays),
          fetchEvents({ kinds: [6], authors: [pubkey], limit: 100 }, readRelays)
        ])
        
        const reactionCounts = {}
        const myReactions = new Set()
        const myReactionIds = {}
        
        for (const event of reactionEvents) {
          const targetId = event.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) {
            reactionCounts[targetId] = (reactionCounts[targetId] || 0) + 1
            if (event.pubkey === pubkey) {
              myReactions.add(targetId)
              myReactionIds[targetId] = event.id
            }
          }
        }

        const myReposts = new Set()
        const myRepostIdsMap = {}
        for (const repost of myRepostEvents) {
          const targetId = repost.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) {
            myReposts.add(targetId)
            myRepostIdsMap[targetId] = repost.id
          }
        }

        setReactions(reactionCounts)
        setUserReactions(myReactions)
        setUserReposts(myReposts)
        setUserReactionIds(myReactionIds)
        setUserRepostIds(myRepostIdsMap)
      }
    } catch (e) {
      console.error('Failed to load full timeline:', e)
    } finally {
      setLoadingMore(false)
    }
  }

  // Load following timeline separately (for desktop dual column)
  const loadFollowingTimeline = async () => {
    if (followList.length === 0) {
      setFollowingPosts([])
      return
    }
    
    setFollowListLoading(true)
    const readRelays = getReadRelays()
    const oneHourAgo = Math.floor(Date.now() / 1000) - 3600
    
    try {
      const noteFilter = { kinds: [1], authors: followList, since: oneHourAgo, limit: 100 }
      const repostFilter = { kinds: [6], authors: followList, since: oneHourAgo, limit: 50 }
      
      const [notes, reposts] = await Promise.all([
        fetchEvents(noteFilter, readRelays),
        fetchEvents(repostFilter, readRelays)
      ])

      const repostData = []
      const originalAuthors = new Set()

      for (const repost of reposts) {
        try {
          if (repost.content) {
            const originalEvent = JSON.parse(repost.content)
            originalAuthors.add(originalEvent.pubkey)
            repostData.push({
              ...originalEvent,
              _repostedBy: repost.pubkey,
              _repostTime: repost.created_at,
              _isRepost: true,
              _repostId: repost.id
            })
          }
        } catch (e) {}
      }

      const allPosts = [...notes, ...repostData].sort((a, b) => {
        const timeA = a._repostTime || a.created_at
        const timeB = b._repostTime || b.created_at
        return timeB - timeA
      })

      setFollowingPosts(allPosts)
      
      // Fetch profiles
      const authors = new Set()
      allPosts.forEach(p => {
        authors.add(p.pubkey)
        if (p._repostedBy) authors.add(p._repostedBy)
      })
      originalAuthors.forEach(a => authors.add(a))
      
      if (authors.size > 0) {
        const profileMap = await fetchProfilesBatch(Array.from(authors))
        setProfiles(prev => ({ ...prev, ...profileMap }))
      }
    } catch (e) {
      console.error('Failed to load following timeline:', e)
    } finally {
      setFollowListLoading(false)
    }
  }

  // Manual refresh
  const loadTimeline = async () => {
    setLoading(true)
    setLoadError(false)
    const readRelays = getReadRelays()
    const oneHourAgo = Math.floor(Date.now() / 1000) - 3600
    const currentMode = timelineMode
    const currentSetPosts = currentMode === 'global' ? setGlobalPosts : setFollowingPosts
    
    try {
      let noteFilter = { kinds: [1], since: oneHourAgo, limit: 100 }
      let repostFilter = { kinds: [6], since: oneHourAgo, limit: 50 }
      
      if (currentMode === 'following' && followList.length > 0) {
        noteFilter.authors = followList
        repostFilter.authors = followList
      } else if (currentMode === 'following' && followList.length === 0) {
        currentSetPosts([])
        setLoading(false)
        return
      }
      
      const [notes, reposts] = await Promise.all([
        fetchEvents(noteFilter, readRelays),
        fetchEvents(repostFilter, readRelays)
      ])
      
      // Check if fetch returned empty (possible connection issue)
      if (notes.length === 0 && reposts.length === 0 && currentMode === 'global') {
        console.warn('No events received - possible connection issue')
        setLoadError(true)
      }

      const repostData = []
      const originalAuthors = new Set()

      for (const repost of reposts) {
        try {
          if (repost.content) {
            const originalEvent = JSON.parse(repost.content)
            originalAuthors.add(originalEvent.pubkey)
            repostData.push({
              ...originalEvent,
              _repostedBy: repost.pubkey,
              _repostTime: repost.created_at,
              _isRepost: true,
              _repostId: repost.id
            })
          }
        } catch (e) {}
      }

      const allPosts = [...notes, ...repostData].sort((a, b) => {
        const timeA = a._repostTime || a.created_at
        const timeB = b._repostTime || b.created_at
        return timeB - timeA
      })

      currentSetPosts(allPosts)
      
      const authors = new Set()
      allPosts.forEach(p => {
        authors.add(p.pubkey)
        if (p._repostedBy) authors.add(p._repostedBy)
      })
      originalAuthors.forEach(a => authors.add(a))
      
      if (authors.size > 0) {
        const profileMap = await fetchProfilesBatch(Array.from(authors))
        setProfiles(prev => ({ ...prev, ...profileMap }))
      }

      if (pubkey && allPosts.length > 0) {
        const eventIds = allPosts.map(p => p.id)
        const [reactionEvents, myRepostEvents] = await Promise.all([
          fetchEvents({ kinds: [7], '#e': eventIds, limit: 500 }, readRelays),
          fetchEvents({ kinds: [6], authors: [pubkey], limit: 100 }, readRelays)
        ])
        
        const reactionCounts = {}
        const myReactions = new Set()
        const myReactionIds = {}
        
        for (const event of reactionEvents) {
          const targetId = event.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) {
            reactionCounts[targetId] = (reactionCounts[targetId] || 0) + 1
            if (event.pubkey === pubkey) {
              myReactions.add(targetId)
              myReactionIds[targetId] = event.id
            }
          }
        }

        const myReposts = new Set()
        const myRepostIdsMap = {}
        for (const repost of myRepostEvents) {
          const targetId = repost.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) {
            myReposts.add(targetId)
            myRepostIdsMap[targetId] = repost.id
          }
        }

        setReactions(reactionCounts)
        setUserReactions(myReactions)
        setUserReposts(myReposts)
        setUserReactionIds(myReactionIds)
        setUserRepostIds(myRepostIdsMap)
      }
    } catch (e) {
      console.error('Failed to load timeline:', e)
      setLoadError(true)
    } finally {
      setLoading(false)
    }
  }

  const handleLike = async (event) => {
    if (!pubkey || userReactions.has(event.id)) return

    setLikeAnimating(event.id)
    setTimeout(() => setLikeAnimating(null), 300)

    try {
      const reactionEvent = createEventTemplate(7, '+', [
        ['e', event.id],
        ['p', event.pubkey]
      ])
      reactionEvent.pubkey = pubkey
      
      const signed = await signEventNip07(reactionEvent)
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
      alert(e.message || 'いいねに失敗しました')
    }
  }

  const handleUnlike = async (event, reactionEventId) => {
    if (!pubkey || !userReactions.has(event.id)) return

    try {
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
  }

  const handleRepost = async (event) => {
    if (!pubkey || userReposts.has(event.id)) return

    try {
      const repostEvent = createEventTemplate(6, JSON.stringify(event), [
        ['e', event.id, '', 'mention'],
        ['p', event.pubkey]
      ])
      repostEvent.pubkey = pubkey
      
      const signed = await signEventNip07(repostEvent)
      const success = await publishEvent(signed)
      
      if (success) {
        setUserReposts(prev => new Set([...prev, event.id]))
        setUserRepostIds(prev => ({ ...prev, [event.id]: signed.id }))
      }
    } catch (e) {
      console.error('Failed to repost:', e)
      alert(e.message || 'リポストに失敗しました')
    }
  }

  const handleUnrepost = async (event, repostEventId) => {
    if (!pubkey || !userReposts.has(event.id)) return

    try {
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
  }

  const handleDelete = async (eventId) => {
    if (!confirm('この投稿を削除しますか？')) return
    
    try {
      const result = await deleteEvent(eventId)
      if (result.success) {
        // Remove from posts list
        setPosts(prev => prev.filter(p => p.id !== eventId && p._repostId !== eventId))
      }
    } catch (e) {
      console.error('Failed to delete:', e)
      alert('削除に失敗しました')
    }
  }

  // Get default zap amount from localStorage
  const getDefaultZapAmount = () => {
    if (typeof window !== 'undefined') {
      const saved = localStorage.getItem('defaultZapAmount')
      return saved ? parseInt(saved, 10) : 21
    }
    return 21
  }

  // Quick zap (single tap)
  const handleZap = async (event) => {
    const profile = profiles[event.pubkey]
    if (!profile?.lud16) {
      alert('この投稿者はLightningアドレスを設定していません')
      return
    }

    setZapAnimating(event.id)
    setTimeout(() => setZapAnimating(null), 600)

    const amount = getDefaultZapAmount()

    try {
      setZapping(true)
      const result = await fetchLightningInvoice(profile.lud16, amount)
      
      if (result.invoice) {
        const copied = await copyToClipboard(result.invoice)
        if (copied) {
          alert(`⚡ ${amount} sats のインボイスをコピーしました！\n\nお好きなLightningウォレットで支払いできます。`)
        } else {
          // Fallback: show invoice for manual copy
          prompt('インボイスをコピーしてください:', result.invoice)
        }
      }
    } catch (e) {
      console.error('Failed to create invoice:', e)
      alert(`インボイスの作成に失敗しました: ${e.message}`)
    } finally {
      setZapping(false)
    }
  }

  // Long press to open zap modal
  const handleZapLongPressStart = (event) => {
    longPressTimerRef.current = setTimeout(() => {
      const profile = profiles[event.pubkey]
      if (!profile?.lud16) {
        alert('この投稿者はLightningアドレスを設定していません')
        return
      }
      setShowZapModal({ event, profile })
      setZapAmount(getDefaultZapAmount().toString())
      setZapComment('')
    }, 500)
  }

  const handleZapLongPressEnd = () => {
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current)
      longPressTimerRef.current = null
    }
  }

  // Send custom zap from modal
  const handleSendCustomZap = async () => {
    if (!showZapModal || zapping) return

    const amount = parseInt(zapAmount, 10)
    if (!amount || amount < 1) {
      alert('有効な金額を入力してください')
      return
    }

    try {
      setZapping(true)
      const result = await fetchLightningInvoice(
        showZapModal.profile.lud16, 
        amount, 
        zapComment
      )
      
      if (result.invoice) {
        const copied = await copyToClipboard(result.invoice)
        setShowZapModal(null)
        if (copied) {
          alert(`⚡ ${amount} sats のインボイスをコピーしました！\n\nお好きなLightningウォレットで支払いできます。`)
        } else {
          prompt('インボイスをコピーしてください:', result.invoice)
        }
      }
    } catch (e) {
      console.error('Failed to create invoice:', e)
      alert(`インボイスの作成に失敗しました: ${e.message}`)
    } finally {
      setZapping(false)
    }
  }

  const handlePostImageUpload = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    
    setUploadingPostImage(true)
    try {
      const url = await uploadImage(file)
      setPostImage(url)
    } catch (err) {
      console.error('Upload failed:', err)
      alert('アップロードに失敗しました')
    } finally {
      setUploadingPostImage(false)
    }
  }

  const handlePost = async () => {
    if ((!newPost.trim() && !postImage) || !pubkey) return
    setPosting(true)

    try {
      // Add image URL to content if present
      let content = newPost.trim()
      if (postImage) {
        content = content ? `${content}\n${postImage}` : postImage
      }
      
      const event = createEventTemplate(1, content)
      event.pubkey = pubkey
      
      // Add client tag
      event.tags = [...event.tags, ['client', 'nullnull']]
      
      // Add emoji tags if any
      if (emojiTags.length > 0) {
        event.tags = [...event.tags, ...emojiTags]
      }
      
      const signed = await signEventNip07(event)
      const success = await publishEvent(signed)
      
      if (success) {
        setPosts([signed, ...posts])
        setNewPost('')
        setPostImage(null)
        setEmojiTags([])
        setShowPostModal(false)
        
        // Notify parent to refresh home tab
        if (onPostPublished) {
          onPostPublished()
        }
      }
    } catch (e) {
      console.error('Failed to post:', e)
      alert(e.message || '投稿に失敗しました')
    } finally {
      setPosting(false)
    }
  }
  
  // Handle emoji selection from picker
  const handleEmojiSelect = (emoji) => {
    // Add :shortcode: to content
    setNewPost(prev => prev + `:${emoji.shortcode}:`)
    // Add emoji tag if not already present
    if (!emojiTags.some(t => t[1] === emoji.shortcode)) {
      setEmojiTags(prev => [...prev, ['emoji', emoji.shortcode, emoji.url]])
    }
    setShowEmojiPicker(false)
  }

  return (
    <div className="min-h-full overflow-x-hidden">
      {/* Header with tabs - fixed position (mobile only) */}
      <header className="fixed top-0 left-0 right-0 lg:left-[240px] xl:left-[280px] z-30 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center justify-between px-4 h-14 lg:h-16">
          {/* Tab Switcher (Mobile only) */}
          <div className="flex items-center gap-1 lg:hidden">
            <button
              onClick={() => handleModeChange('global')}
              className={`px-3 py-1.5 text-sm rounded-full transition-all ${
                timelineMode === 'global'
                  ? 'bg-[var(--line-green)] text-white font-medium shadow-sm'
                  : 'text-[var(--text-tertiary)] hover:bg-[var(--bg-tertiary)]'
              }`}
            >
              リレー
            </button>
            <button
              onClick={() => handleModeChange('following')}
              className={`px-3 py-1.5 text-sm rounded-full transition-all ${
                timelineMode === 'following'
                  ? 'bg-[var(--line-green)] text-white font-medium shadow-sm'
                  : 'text-[var(--text-tertiary)] hover:bg-[var(--bg-tertiary)]'
              }`}
            >
              フォロー
            </button>
          </div>
          
          {/* Desktop Title */}
          <h1 className="hidden lg:block text-lg font-bold text-[var(--text-primary)]">タイムライン</h1>
          
          {/* Desktop Search Bar */}
          <div className="hidden lg:flex flex-1 max-w-md mx-8">
            <button
              onClick={() => setShowSearch(true)}
              className="w-full flex items-center gap-3 px-5 py-2.5 rounded-full bg-[var(--bg-secondary)] border border-[var(--border-color)] text-[var(--text-tertiary)] hover:border-[var(--text-tertiary)] transition-all"
            >
              <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="11" cy="11" r="8"/>
                <line x1="21" y1="21" x2="16.65" y2="16.65"/>
              </svg>
              <span className="text-sm">検索...</span>
            </button>
          </div>
          
          {/* Mobile Search Button */}
          <button
            onClick={() => setShowSearch(true)}
            className="lg:hidden text-[var(--text-secondary)] action-btn p-2"
          >
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="8"/>
              <line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
          </button>
          
          {/* Spacer for desktop (refresh buttons moved to columns) */}
          <div className="hidden lg:block w-20" />
        </div>
      </header>
      
      {/* Spacer for fixed header */}
      <div className="h-14 lg:h-16" />

      {/* Search Modal */}
      {showSearch && (
        <SearchModal
          pubkey={pubkey}
          onClose={() => setShowSearch(false)}
          onViewProfile={(pk) => {
            setShowSearch(false)
            setViewingProfile(pk)
          }}
        />
      )}

      {/* Post Modal */}
      {showPostModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => { setShowPostModal(false); setPostImage(null) }}>
          <div 
            className="w-full h-full sm:h-auto sm:max-w-lg bg-[var(--bg-primary)] sm:rounded-2xl flex flex-col overflow-hidden animate-scaleIn"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)] flex-shrink-0">
              <button onClick={() => { setShowPostModal(false); setPostImage(null) }} className="text-[var(--text-secondary)] action-btn">
                キャンセル
              </button>
              <span className="font-semibold text-[var(--text-primary)]">新規投稿</span>
              <button
                onClick={handlePost}
                disabled={posting || (!newPost.trim() && !postImage)}
                className="btn-line text-sm py-1.5 px-4 disabled:opacity-50"
              >
                {posting ? '...' : '投稿'}
              </button>
            </div>
            <div className="flex-1 p-4 pb-20 sm:pb-4 flex flex-col overflow-y-auto">
              <textarea
                value={newPost}
                onChange={(e) => setNewPost(e.target.value)}
                className="w-full min-h-[120px] sm:min-h-[150px] bg-transparent resize-none text-[var(--text-primary)] placeholder-[var(--text-tertiary)] outline-none text-base"
                placeholder="いまどうしてる？"
                autoFocus
              />
              
              {/* Image preview */}
              {postImage && (
                <div className="relative mt-3 rounded-xl overflow-hidden flex-shrink-0">
                  <img src={postImage} alt="Upload preview" className="w-full max-h-48 object-cover rounded-xl" />
                  <button
                    onClick={() => setPostImage(null)}
                    className="absolute top-2 right-2 w-8 h-8 bg-black/60 rounded-full flex items-center justify-center text-white"
                  >
                    <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <line x1="18" y1="6" x2="6" y2="18"/>
                      <line x1="6" y1="6" x2="18" y2="18"/>
                    </svg>
                  </button>
                </div>
              )}
              
              {/* Image upload and emoji picker buttons */}
              <div className="mt-3 pt-3 border-t border-[var(--border-color)] flex-shrink-0">
                <input
                  ref={postImageInputRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={handlePostImageUpload}
                />
                <div className="flex items-center gap-4">
                  <button
                    onClick={() => postImageInputRef.current?.click()}
                    disabled={uploadingPostImage}
                    className="flex items-center gap-2 text-[var(--line-green)] text-sm"
                  >
                    {uploadingPostImage ? (
                      <>
                        <svg className="w-5 h-5 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                          <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                        </svg>
                        アップロード中...
                      </>
                    ) : (
                      <>
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                          <circle cx="8.5" cy="8.5" r="1.5"/>
                          <polyline points="21 15 16 10 5 21"/>
                        </svg>
                        画像
                      </>
                    )}
                  </button>
                  
                  {/* Emoji picker button */}
                  <button
                    onClick={() => setShowEmojiPicker(!showEmojiPicker)}
                    className={`flex items-center gap-2 text-sm ${showEmojiPicker ? 'text-[var(--line-green)]' : 'text-[var(--text-tertiary)]'}`}
                  >
                    <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10"/>
                      <path d="M8 14s1.5 2 4 2 4-2 4-2"/>
                      <line x1="9" y1="9" x2="9.01" y2="9"/>
                      <line x1="15" y1="9" x2="15.01" y2="9"/>
                    </svg>
                    絵文字
                  </button>
                </div>
                
                {/* Emoji Picker - displayed below buttons */}
                {showEmojiPicker && (
                  <div className="mt-3">
                    <EmojiPicker
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
      )}

      {/* Zap Modal */}
      {showZapModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowZapModal(null)}>
          <div 
            className="w-full max-w-sm mx-4 bg-[var(--bg-primary)] rounded-2xl overflow-hidden animate-scaleIn"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="p-4 border-b border-[var(--border-color)]">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-bold text-[var(--text-primary)]">⚡ Zap送信</h3>
                <button onClick={() => setShowZapModal(null)} className="text-[var(--text-tertiary)] action-btn p-1">
                  <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <line x1="18" y1="6" x2="6" y2="18"/>
                    <line x1="6" y1="6" x2="18" y2="18"/>
                  </svg>
                </button>
              </div>
              <p className="text-sm text-[var(--text-secondary)] mt-1">
                {showZapModal.profile.name || shortenPubkey(showZapModal.event.pubkey)}
              </p>
            </div>

            <div className="p-4 space-y-4">
              <div>
                <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">金額 (sats)</label>
                <input
                  type="number"
                  value={zapAmount}
                  onChange={(e) => setZapAmount(e.target.value)}
                  className="input-line"
                  placeholder="21"
                  min="1"
                />
              </div>

              <div className="flex flex-wrap gap-2">
                {[21, 100, 500, 1000, 5000].map(amount => (
                  <button
                    key={amount}
                    onClick={() => setZapAmount(amount.toString())}
                    className={`px-3 py-1 rounded-full text-sm ${
                      zapAmount === amount.toString()
                        ? 'bg-yellow-500 text-black'
                        : 'bg-[var(--bg-secondary)] text-[var(--text-primary)]'
                    }`}
                  >
                    ⚡{amount}
                  </button>
                ))}
              </div>

              <div>
                <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">コメント (任意)</label>
                <input
                  type="text"
                  value={zapComment}
                  onChange={(e) => setZapComment(e.target.value)}
                  className="input-line"
                  placeholder="Zap!"
                  maxLength={100}
                />
              </div>
            </div>

            <div className="p-4 border-t border-[var(--border-color)]">
              <button
                onClick={handleSendCustomZap}
                disabled={zapping || !zapAmount}
                className="w-full btn-line py-3 disabled:opacity-50"
              >
                {zapping ? '作成中...' : `⚡ ${zapAmount || 0} sats のインボイスを作成`}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* FAB - Post Button (Hidden on desktop where sidebar has compose button) */}
      <button
        onClick={() => setShowPostModal(true)}
        className="fab lg:hidden"
      >
        <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <line x1="12" y1="5" x2="12" y2="19"/>
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
      </button>
      
      {/* Desktop FAB */}
      <button
        onClick={() => setShowPostModal(true)}
        className="hidden lg:flex fixed bottom-8 right-8 w-14 h-14 rounded-full bg-[var(--line-green)] text-white items-center justify-center shadow-lg hover:scale-105 hover:shadow-xl transition-all z-30"
      >
        <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <line x1="12" y1="5" x2="12" y2="19"/>
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
      </button>

      {/* Timeline */}
      {/* Mobile: Single column with tab switching */}
      <div className="lg:hidden">
        {loading ? (
          <div className="divide-y divide-[var(--border-color)]">
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="p-4">
                <div className="flex items-start gap-3">
                  <div className="w-10 h-10 rounded-full skeleton" />
                  <div className="flex-1">
                    <div className="skeleton h-4 w-24 rounded mb-2" />
                    <div className="skeleton h-4 w-full rounded mb-1" />
                    <div className="skeleton h-4 w-2/3 rounded" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : posts.length === 0 || loadError ? (
          <div className="px-4 py-16 text-center">
            <div className="w-20 h-20 mx-auto mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
              {loadError ? (
                <svg className="w-10 h-10 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                  <line x1="12" y1="9" x2="12" y2="13"/>
                  <line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
              ) : timelineMode === 'following' ? (
                <svg className="w-10 h-10 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
                  <circle cx="9" cy="7" r="4"/>
                  <path d="M23 21v-2a4 4 0 0 0-3-3.87"/>
                  <path d="M16 3.13a4 4 0 0 1 0 7.75"/>
                </svg>
              ) : (
                <svg className="w-10 h-10 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                  <line x1="3" y1="9" x2="21" y2="9"/>
                  <line x1="9" y1="21" x2="9" y2="9"/>
                </svg>
              )}
            </div>
            <p className="text-[var(--text-secondary)]">
              {loadError
                ? '接続に問題が発生しました'
                : timelineMode === 'following' 
                  ? (followList.length === 0 ? 'まだ誰もフォローしていません' : 'フォロー中のユーザーの投稿がありません')
                  : '投稿がありません'}
            </p>
            {loadError ? (
              <button
                onClick={() => loadTimeline()}
                className="mt-4 px-6 py-2 bg-[var(--line-green)] text-white rounded-full text-sm font-medium"
              >
                再読み込み
              </button>
            ) : timelineMode === 'following' && followList.length === 0 && (
              <p className="text-xs text-[var(--text-tertiary)] mt-2">
                プロフィールページでユーザーをフォローしてみましょう
              </p>
            )}
          </div>
        ) : (
          <div className="divide-y divide-[var(--border-color)]">
            {posts
              .filter(post => !mutedPubkeys.has(post.pubkey))
              .map((post, index) => {
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
                    myReactionId={userReactionIds[post.id]}
                    myRepostId={userRepostIds[post.id]}
                    isLiking={isLiking}
                    isZapping={isZapping}
                    onLike={handleLike}
                    onUnlike={handleUnlike}
                    onRepost={handleRepost}
                    onUnrepost={handleUnrepost}
                    onZap={handleZap}
                    onZapLongPress={handleZapLongPressStart}
                    onZapLongPressEnd={handleZapLongPressEnd}
                    onAvatarClick={handleAvatarClick}
                    onMute={handleMute}
                    onDelete={handleDelete}
                    isOwnPost={post.pubkey === pubkey}
                    isRepost={post._isRepost}
                    repostedBy={post._repostedBy ? profiles[post._repostedBy] || { pubkey: post._repostedBy } : null}
                  />
                </div>
              )
            })}
            
            {loadingMore && (
              <div className="flex justify-center py-4">
                <div className="flex items-center gap-2 text-[var(--text-tertiary)] text-sm">
                  <svg className="w-4 h-4 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                    <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                  </svg>
                  読み込み中...
                </div>
              </div>
            )}
          </div>
        )}
      </div>
      
      {/* Desktop: Dual column (Relay | Following) with independent scroll */}
      <div className="hidden lg:flex lg:fixed lg:top-16 lg:bottom-0 lg:left-[240px] xl:left-[280px] lg:right-0">
        {/* Left column: Relay timeline */}
        <div className="flex-1 flex flex-col border-r border-[var(--border-color)] overflow-hidden">
          <div className="flex-shrink-0 bg-[var(--bg-primary)] border-b border-[var(--border-color)] px-4 py-3 flex items-center justify-between">
            <h2 className="font-bold text-[var(--text-primary)]">リレー</h2>
            <button
              onClick={() => loadTimeline()}
              disabled={loading}
              className="p-2 rounded-full text-[var(--text-tertiary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)] transition-all disabled:opacity-50"
              title="リレーを更新"
            >
              <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M23 4v6h-6M1 20v-6h6"/>
                <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/>
              </svg>
            </button>
          </div>
          <div className="flex-1 overflow-y-auto">
            {loading ? (
              <div className="divide-y divide-[var(--border-color)]">
                {[1, 2, 3].map((i) => (
                  <div key={i} className="p-4">
                    <div className="flex items-start gap-3">
                      <div className="w-10 h-10 rounded-full skeleton" />
                      <div className="flex-1">
                        <div className="skeleton h-4 w-24 rounded mb-2" />
                        <div className="skeleton h-4 w-full rounded mb-1" />
                        <div className="skeleton h-4 w-2/3 rounded" />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            ) : globalPosts.length === 0 ? (
              <div className="px-4 py-16 text-center text-[var(--text-tertiary)]">
                投稿がありません
              </div>
            ) : (
              <div className="divide-y divide-[var(--border-color)]">
                {globalPosts
                  .filter(post => !mutedPubkeys.has(post.pubkey))
                  .map((post, index) => {
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
                        myReactionId={userReactionIds[post.id]}
                        myRepostId={userRepostIds[post.id]}
                        isLiking={isLiking}
                        isZapping={isZapping}
                        onLike={handleLike}
                        onUnlike={handleUnlike}
                        onRepost={handleRepost}
                        onUnrepost={handleUnrepost}
                        onZap={handleZap}
                        onZapLongPress={handleZapLongPressStart}
                        onZapLongPressEnd={handleZapLongPressEnd}
                        onAvatarClick={handleAvatarClick}
                        onMute={handleMute}
                        onDelete={handleDelete}
                        isOwnPost={post.pubkey === pubkey}
                        isRepost={post._isRepost}
                        repostedBy={post._repostedBy ? profiles[post._repostedBy] || { pubkey: post._repostedBy } : null}
                      />
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
        
        {/* Right column: Following timeline */}
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="flex-shrink-0 bg-[var(--bg-primary)] border-b border-[var(--border-color)] px-4 py-3 flex items-center justify-between">
            <h2 className="font-bold text-[var(--text-primary)]">フォロー</h2>
            <button
              onClick={() => loadFollowingTimeline()}
              disabled={followListLoading}
              className="p-2 rounded-full text-[var(--text-tertiary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)] transition-all disabled:opacity-50"
              title="フォローを更新"
            >
              <svg className={`w-4 h-4 ${followListLoading ? 'animate-spin' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M23 4v6h-6M1 20v-6h6"/>
                <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/>
              </svg>
            </button>
          </div>
          <div className="flex-1 overflow-y-auto">
            {followListLoading ? (
              <div className="divide-y divide-[var(--border-color)]">
                {[1, 2, 3].map((i) => (
                  <div key={i} className="p-4">
                    <div className="flex items-start gap-3">
                      <div className="w-10 h-10 rounded-full skeleton" />
                      <div className="flex-1">
                        <div className="skeleton h-4 w-24 rounded mb-2" />
                        <div className="skeleton h-4 w-full rounded mb-1" />
                        <div className="skeleton h-4 w-2/3 rounded" />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            ) : followingPosts.length === 0 ? (
              <div className="px-4 py-16 text-center">
                <p className="text-[var(--text-tertiary)]">
                  {followList.length === 0 ? 'まだ誰もフォローしていません' : 'フォロー中のユーザーの投稿がありません'}
                </p>
                {followList.length === 0 && (
                  <p className="text-xs text-[var(--text-tertiary)] mt-2">
                    プロフィールページでユーザーをフォローしてみましょう
                  </p>
                )}
              </div>
            ) : (
              <div className="divide-y divide-[var(--border-color)]">
                {followingPosts
                  .filter(post => !mutedPubkeys.has(post.pubkey))
                  .map((post, index) => {
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
                        myReactionId={userReactionIds[post.id]}
                        myRepostId={userRepostIds[post.id]}
                        isLiking={isLiking}
                        isZapping={isZapping}
                        onLike={handleLike}
                        onUnlike={handleUnlike}
                        onRepost={handleRepost}
                        onUnrepost={handleUnrepost}
                        onZap={handleZap}
                        onZapLongPress={handleZapLongPressStart}
                        onZapLongPressEnd={handleZapLongPressEnd}
                        onAvatarClick={handleAvatarClick}
                        onMute={handleMute}
                        onDelete={handleDelete}
                        isOwnPost={post.pubkey === pubkey}
                        isRepost={post._isRepost}
                        repostedBy={post._repostedBy ? profiles[post._repostedBy] || { pubkey: post._repostedBy } : null}
                      />
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* User Profile View */}
      {viewingProfile && (
        <UserProfileView
          targetPubkey={viewingProfile}
          myPubkey={pubkey}
          onClose={() => setViewingProfile(null)}
          onMute={(targetPubkey) => {
            setMutedPubkeys(prev => new Set([...prev, targetPubkey]))
          }}
          onStartDM={(targetPubkey) => {
            setViewingProfile(null)
            if (onStartDM) onStartDM(targetPubkey)
          }}
        />
      )}
    </div>
  )
})

export default TimelineTab
