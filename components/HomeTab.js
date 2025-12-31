'use client'

import { useState, useEffect, useRef, forwardRef, useImperativeHandle } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchEvents,
  parseProfile,
  signEventNip07,
  createEventTemplate,
  publishEvent,
  deleteEvent,
  unlikeEvent,
  unrepostEvent,
  shortenPubkey,
  formatTimestamp,
  hasNip07,
  verifyNip05,
  encodeNpub,
  uploadImage,
  fetchProfileCached,
  fetchProfilesBatch,
  getAllCachedProfiles,
  fetchFollowListCached,
  DEFAULT_RELAY,
  RELAYS
} from '@/lib/nostr'
import { setCachedProfile, getCachedProfile, setCachedFollowList } from '@/lib/cache'
import PostItem from './PostItem'
import UserProfileView from './UserProfileView'
import EmojiPicker from './EmojiPicker'
import BadgeDisplay, { clearBadgeCache } from './BadgeDisplay'

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

  // Build emoji map from selected emojis (format: ['emoji', shortcode, url])
  const emojiMap = {}
  customEmojis.forEach(emoji => {
    if (emoji[0] === 'emoji' && emoji[1] && emoji[2]) {
      emojiMap[emoji[1]] = emoji[2]
    }
  })

  // Split by hashtags and custom emoji shortcodes
  const combinedRegex = /(#[^\s#\u3000]+|:[a-zA-Z0-9_]+:)/g
  const parts = content.split(combinedRegex).filter(Boolean)

  return (
    <div className="text-base text-[var(--text-primary)] whitespace-pre-wrap break-words">
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
                  e.target.style.display = 'none'
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

// Format birthday to string (handles both string and object formats)
function formatBirthday(birthday) {
  if (!birthday) return ''
  if (typeof birthday === 'string') return birthday
  if (typeof birthday === 'object') {
    // Handle {month, day} or {year, month, day} format
    const month = birthday.month ? String(birthday.month).padStart(2, '0') : '??'
    const day = birthday.day ? String(birthday.day).padStart(2, '0') : '??'
    if (birthday.year) {
      return `${birthday.year}-${month}-${day}`
    }
    return `${month}-${day}`
  }
  return String(birthday)
}

// NIP-05 Badge for profile section
function ProfileNip05Badge({ nip05, pubkey }) {
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
    // _@domain -> @domain
    display = nip05.slice(1)
  } else if (!nip05.includes('@')) {
    // domain -> @domain
    display = `@${nip05}`
  }

  return (
    <div className="flex items-center gap-1 text-sm text-[var(--line-green)] mt-1">
      <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
        <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/>
      </svg>
      <span>{display}</span>
    </div>
  )
}

const HomeTab = forwardRef(function HomeTab({ pubkey, onLogout, onStartDM, onHashtagClick }, ref) {
  const [profile, setProfile] = useState(null)
  const [rawProfile, setRawProfile] = useState(null) // Store original profile JSON
  const [posts, setPosts] = useState([])
  const [profiles, setProfiles] = useState({})
  const [loading, setLoading] = useState(true)
  const [isEditing, setIsEditing] = useState(false)
  const [editForm, setEditForm] = useState({
    name: '',
    about: '',
    picture: '',
    banner: '',
    nip05: '',
    lud16: '',
    website: '',
    birthday: ''
  })
  const [showPostModal, setShowPostModal] = useState(false)
  const [newPost, setNewPost] = useState('')
  const [postImage, setPostImage] = useState(null) // Image URL for post
  const [uploadingPostImage, setUploadingPostImage] = useState(false)
  const [posting, setPosting] = useState(false)
  const [reactions, setReactions] = useState({})
  const [userReactions, setUserReactions] = useState(new Set())
  const [userReposts, setUserReposts] = useState(new Set())
  const [userReactionIds, setUserReactionIds] = useState({}) // eventId -> reactionEventId
  const [userRepostIds, setUserRepostIds] = useState({}) // eventId -> repostEventId
  const [likedPosts, setLikedPosts] = useState([]) // Posts user has liked
  const [activeSection, setActiveSection] = useState('posts') // 'posts' or 'likes'
  const [likeAnimating, setLikeAnimating] = useState(null)
  const [zapAnimating, setZapAnimating] = useState(null)
  const [copied, setCopied] = useState(false)
  const [viewingProfile, setViewingProfile] = useState(null)
  const [uploadingPicture, setUploadingPicture] = useState(false)
  const [uploadingBanner, setUploadingBanner] = useState(false)
  const [showEmojiPicker, setShowEmojiPicker] = useState(false)
  const [emojiTags, setEmojiTags] = useState([])
  const [contentWarning, setContentWarning] = useState('') // Content warning text (NIP-36)
  const [showCWInput, setShowCWInput] = useState(false) // Toggle CW input visibility
  // Follow list state
  const [followList, setFollowList] = useState([])
  const [followListLoading, setFollowListLoading] = useState(false)
  const [showFollowList, setShowFollowList] = useState(false)
  const [followProfiles, setFollowProfiles] = useState({})
  const [unfollowing, setUnfollowing] = useState(null) // pubkey being unfollowed
  const pictureInputRef = useRef(null)
  const bannerInputRef = useRef(null)
  const postImageInputRef = useRef(null)

  // Expose refresh function to parent
  useImperativeHandle(ref, () => ({
    refresh: () => {
      loadProfile()
      loadPosts()
    },
    closeProfile: () => setViewingProfile(null)
  }))

  useEffect(() => {
    if (pubkey) {
      // Load cached profiles immediately for instant display
      const cachedProfiles = getAllCachedProfiles()
      if (Object.keys(cachedProfiles).length > 0) {
        setProfiles(cachedProfiles)
      }
      
      // Try cached profile first for instant display
      const cached = getCachedProfile(pubkey)
      if (cached) {
        setProfile(cached)
        setEditForm({
          name: cached?.name || '',
          about: cached?.about || '',
          picture: cached?.picture || '',
          banner: cached?.banner || '',
          nip05: cached?.nip05 || '',
          lud16: cached?.lud16 || '',
          website: cached?.website || '',
          birthday: formatBirthday(cached?.birthday) || ''
        })
      }
      
      loadProfile()
      loadPosts()
    }
  }, [pubkey])

  // Lock body scroll when modal is open
  useEffect(() => {
    if (isEditing || showPostModal || viewingProfile || showFollowList) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [isEditing, showPostModal, viewingProfile, showFollowList])

  const loadProfile = async () => {
    if (!pubkey) return
    
    try {
      const events = await fetchEvents(
        { kinds: [0], authors: [pubkey], limit: 1 },
        RELAYS
      )
      
      if (events.length > 0) {
        const p = parseProfile(events[0])
        // Store raw profile JSON for preserving all fields on save
        let rawData = {}
        try {
          rawData = JSON.parse(events[0].content)
          setRawProfile(rawData)
        } catch (e) {
          setRawProfile({})
        }
        setProfile(p)
        setProfiles(prev => ({ ...prev, [pubkey]: p }))
        
        // Cache the profile
        if (p) {
          setCachedProfile(pubkey, { ...p, website: rawData?.website || '' })
        }
        
        setEditForm({
          name: p?.name || '',
          about: p?.about || '',
          picture: p?.picture || '',
          banner: p?.banner || '',
          nip05: p?.nip05 || '',
          lud16: p?.lud16 || '',
          website: rawData?.website || '',
          birthday: formatBirthday(rawData?.birthday) || ''
        })
      }
    } catch (e) {
      console.error('Failed to load profile:', e)
    }
  }

  const loadFollowList = async () => {
    if (!pubkey || followListLoading) return
    setFollowListLoading(true)
    
    try {
      const follows = await fetchFollowListCached(pubkey, RELAYS)
      setFollowList(follows)
      
      // Load profiles for follow list
      if (follows.length > 0) {
        const profiles = await fetchProfilesBatch(follows.slice(0, 100), RELAYS)
        setFollowProfiles(prev => ({ ...prev, ...profiles }))
      }
    } catch (e) {
      console.error('Failed to load follow list:', e)
    } finally {
      setFollowListLoading(false)
    }
  }

  const handleUnfollow = async (targetPubkey) => {
    if (!pubkey || unfollowing) return
    
    if (!confirm('このユーザーのフォローを解除しますか？')) return
    
    setUnfollowing(targetPubkey)
    
    try {
      // Get current follow list event to preserve relay hints
      const followEvents = await fetchEvents({
        kinds: [3],
        authors: [pubkey],
        limit: 1
      }, RELAYS)
      
      // Build new tags without the target pubkey
      let newTags = []
      if (followEvents.length > 0) {
        newTags = followEvents[0].tags.filter(tag => 
          !(tag[0] === 'p' && tag[1] === targetPubkey)
        )
      } else {
        // No existing follow list, create from current state minus target
        newTags = followList
          .filter(pk => pk !== targetPubkey)
          .map(pk => ['p', pk])
      }
      
      // Create and publish new follow list
      const event = createEventTemplate(3, '')
      event.tags = newTags
      
      const signedEvent = await signEventNip07(event)
      if (!signedEvent) {
        throw new Error('署名に失敗しました')
      }
      
      await publishEvent(signedEvent, RELAYS)
      
      // Update local state
      setFollowList(prev => prev.filter(pk => pk !== targetPubkey))
      
      // Clear cached follow list
      setCachedFollowList(pubkey, null)
    } catch (e) {
      console.error('Failed to unfollow:', e)
      alert('フォロー解除に失敗しました: ' + e.message)
    } finally {
      setUnfollowing(null)
    }
  }

  // Load follow list count on mount
  useEffect(() => {
    if (pubkey) {
      // Load follow list in background for count
      fetchFollowListCached(pubkey, RELAYS).then(follows => {
        setFollowList(follows)
      }).catch(e => console.error('Failed to load follow count:', e))
    }
  }, [pubkey])

  const loadPosts = async () => {
    if (!pubkey) return
    setLoading(true)
    
    const oneDayAgo = Math.floor(Date.now() / 1000) - 86400 // 24 hours
    
    try {
      // Fetch notes, reposts, and user's reactions in parallel
      const [notes, reposts, myReactionEvents] = await Promise.all([
        fetchEvents({ kinds: [1], authors: [pubkey], since: oneDayAgo, limit: 50 }, RELAYS),
        fetchEvents({ kinds: [6], authors: [pubkey], since: oneDayAgo, limit: 30 }, RELAYS),
        fetchEvents({ kinds: [7], authors: [pubkey], since: oneDayAgo, limit: 50 }, RELAYS)
      ])

      // Parse reposted events
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
          } else {
            const eTag = repost.tags.find(t => t[0] === 'e')
            if (eTag) {
              const [originalEvent] = await fetchEvents(
                { ids: [eTag[1]], limit: 1 },
                RELAYS
              )
              if (originalEvent) {
                originalAuthors.add(originalEvent.pubkey)
                repostData.push({
                  ...originalEvent,
                  _repostedBy: repost.pubkey,
                  _repostTime: repost.created_at,
                  _isRepost: true,
                  _repostId: repost.id
                })
              }
            }
          }
        } catch (e) {
          console.error('Failed to parse repost:', e)
        }
      }

      // Fetch liked post IDs and get the original posts
      const likedPostIds = []
      for (const reaction of myReactionEvents) {
        const targetId = reaction.tags.find(t => t[0] === 'e')?.[1]
        if (targetId) likedPostIds.push(targetId)
      }

      let likedPostsData = []
      if (likedPostIds.length > 0) {
        const likedEvents = await fetchEvents({ ids: likedPostIds, limit: 50 }, RELAYS)
        // Add authors to fetch profiles
        for (const event of likedEvents) {
          originalAuthors.add(event.pubkey)
        }
        likedPostsData = likedEvents.sort((a, b) => b.created_at - a.created_at)
      }
      setLikedPosts(likedPostsData)

      // Fetch profiles for original authors using batch with cache
      if (originalAuthors.size > 0) {
        const profileMap = await fetchProfilesBatch(Array.from(originalAuthors))
        setProfiles(prev => ({ ...prev, ...profileMap }))
      }

      // Combine and sort by time
      const allPosts = [...notes, ...repostData].sort((a, b) => {
        const timeA = a._repostTime || a.created_at
        const timeB = b._repostTime || b.created_at
        return timeB - timeA
      })

      setPosts(allPosts)

      // Fetch reactions for all posts (own posts + liked posts)
      const allPostIds = [...allPosts.map(p => p.id), ...likedPostIds]
      if (allPostIds.length > 0) {
        const reactionEvents = await fetchEvents(
          { kinds: [7], '#e': allPostIds, limit: 500 },
          RELAYS
        )

        const reactionCounts = {}
        const myReactions = new Set()
        const myReactionIds = {} // eventId -> reactionEventId

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

        setReactions(reactionCounts)
        setUserReactions(myReactions)
        setUserReactionIds(myReactionIds)

        // Fetch user's reposts to track repost IDs
        const myRepostEvents = await fetchEvents(
          { kinds: [6], authors: [pubkey], limit: 100 },
          RELAYS
        )
        const myReposts = new Set()
        const myRepostIdsMap = {}
        for (const repost of myRepostEvents) {
          const targetId = repost.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) {
            myReposts.add(targetId)
            myRepostIdsMap[targetId] = repost.id
          }
        }
        setUserReposts(myReposts)
        setUserRepostIds(myRepostIdsMap)
      }
    } catch (e) {
      console.error('Failed to load posts:', e)
    } finally {
      setLoading(false)
    }
  }

  const handlePictureUpload = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    
    setUploadingPicture(true)
    try {
      const url = await uploadImage(file)
      setEditForm(prev => ({ ...prev, picture: url }))
    } catch (err) {
      console.error('Upload failed:', err)
      alert('アップロードに失敗しました')
    } finally {
      setUploadingPicture(false)
    }
  }

  const handleBannerUpload = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    
    setUploadingBanner(true)
    try {
      const url = await uploadImage(file)
      setEditForm(prev => ({ ...prev, banner: url }))
    } catch (err) {
      console.error('Upload failed:', err)
      alert('アップロードに失敗しました')
    } finally {
      setUploadingBanner(false)
    }
  }

  const handleSaveProfile = async () => {
    try {
      // Start with raw profile to preserve any existing fields
      const profileData = {
        ...(rawProfile || {}),
        name: editForm.name,
        display_name: editForm.name,
        about: editForm.about,
        picture: editForm.picture
      }
      
      // Add optional fields (set to string or remove if empty)
      if (editForm.banner) {
        profileData.banner = editForm.banner
      }
      if (editForm.nip05) {
        profileData.nip05 = editForm.nip05
      } else {
        delete profileData.nip05
      }
      if (editForm.lud16) {
        profileData.lud16 = editForm.lud16
      } else {
        delete profileData.lud16
      }
      if (editForm.website) {
        profileData.website = editForm.website
      } else {
        delete profileData.website
      }
      if (editForm.birthday) {
        profileData.birthday = editForm.birthday
      } else {
        delete profileData.birthday
      }
      
      const event = createEventTemplate(0, JSON.stringify(profileData))
      event.pubkey = pubkey
      
      const signedEvent = await signEventNip07(event)
      const success = await publishEvent(signedEvent)
      
      if (success) {
        // Update raw profile
        setRawProfile(profileData)
        
        const newProfile = {
          ...profile,
          name: editForm.name,
          displayName: editForm.name,
          about: editForm.about,
          picture: editForm.picture,
          banner: editForm.banner,
          nip05: editForm.nip05,
          lud16: editForm.lud16,
          website: editForm.website,
          birthday: editForm.birthday
        }
        setProfile(newProfile)
        setProfiles(prev => ({ ...prev, [pubkey]: newProfile }))
        setIsEditing(false)
      }
    } catch (e) {
      console.error('Failed to save profile:', e)
      alert('プロフィールの保存に失敗しました: ' + e.message)
    }
  }

  const handleCopyPubkey = async () => {
    try {
      const npub = encodeNpub(pubkey)
      await navigator.clipboard.writeText(npub || pubkey)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch (e) {
      console.error('Failed to copy:', e)
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
    if (!newPost.trim() && !postImage) return
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

      // Content warning tag (NIP-36)
      if (contentWarning.trim()) {
        event.tags = [...event.tags, ['content-warning', contentWarning.trim()]]
      }

      // Hashtag tags (NIP-01)
      const hashtags = extractHashtags(content)
      if (hashtags.length > 0) {
        const hashtagTags = hashtags.map(tag => ['t', tag])
        event.tags = [...event.tags, ...hashtagTags]
      }

      const signedEvent = await signEventNip07(event)
      const success = await publishEvent(signedEvent)

      if (success) {
        setPosts([signedEvent, ...posts])
        setNewPost('')
        setPostImage(null)
        setEmojiTags([])
        setContentWarning('')
        setShowCWInput(false)
        setShowPostModal(false)
      }
    } catch (e) {
      console.error('Failed to post:', e)
      alert('投稿に失敗しました: ' + e.message)
    } finally {
      setPosting(false)
    }
  }
  
  // Handle emoji selection from picker
  const handleEmojiSelect = (emoji) => {
    setNewPost(prev => prev + `:${emoji.shortcode}:`)
    if (!emojiTags.some(t => t[1] === emoji.shortcode)) {
      setEmojiTags(prev => [...prev, ['emoji', emoji.shortcode, emoji.url]])
    }
    setShowEmojiPicker(false)
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
        ['e', event.id, DEFAULT_RELAY],
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

  const handleZap = (event) => {
    const postProfile = profiles[event.pubkey]
    if (!postProfile?.lud16) {
      alert('この投稿者はLightningアドレスを設定していません')
      return
    }
    setZapAnimating(event.id)
    setTimeout(() => setZapAnimating(null), 300)
    alert(`⚡ Zap送信\n\n対象: ${postProfile.name || shortenPubkey(event.pubkey)}\nLN: ${postProfile.lud16}`)
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

  const npub = pubkey ? nip19.npubEncode(pubkey) : ''

  return (
    <div className="min-h-full">
      {/* Header */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center justify-between px-4 h-12">
          <h1 className="text-lg font-semibold text-[var(--text-primary)]">ホーム</h1>
          <button
            onClick={onLogout}
            className="text-sm text-[var(--text-secondary)] action-btn"
          >
            ログアウト
          </button>
        </div>
      </header>

      {/* Profile Section */}
      <div className="animate-fadeIn">
        {/* Banner */}
        <div 
          className="h-28 bg-gradient-to-br from-[#06C755] to-[#04A347]"
          style={profile?.banner ? { 
            backgroundImage: `url(${profile.banner})`,
            backgroundSize: 'cover',
            backgroundPosition: 'center'
          } : {}}
        />
        
        {/* Profile Card */}
        <div className="relative px-4 -mt-12">
          <div className="bg-[var(--bg-primary)] rounded-2xl p-4 shadow-sm">
            <div className="flex items-start gap-3">
              {/* Avatar */}
              <div className="relative -mt-10">
                <div className="w-20 h-20 rounded-full overflow-hidden border-4 border-[var(--bg-primary)] bg-[var(--bg-tertiary)]">
                  {profile?.picture ? (
                    <img 
                      src={profile.picture} 
                      alt="" 
                      className="w-full h-full object-cover"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center">
                      <svg className="w-10 h-10 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                      </svg>
                    </div>
                  )}
                </div>
              </div>
              
              {/* Info */}
              <div className="flex-1 min-w-0 pt-1">
                <div className="flex items-center gap-2">
                  <h2 className="text-lg font-bold text-[var(--text-primary)] truncate">
                    {profile?.name || 'Anonymous'}
                  </h2>
                  <button
                    onClick={() => setIsEditing(true)}
                    className="text-[var(--text-tertiary)] action-btn p-1"
                  >
                    <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/>
                      <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/>
                    </svg>
                  </button>
                </div>
                {/* NIP-05 verified badge */}
                {profile?.nip05 && (
                  <ProfileNip05Badge nip05={profile.nip05} pubkey={pubkey} />
                )}
                {/* Pubkey with copy button */}
                <button
                  onClick={handleCopyPubkey}
                  className="flex items-center gap-1 text-xs text-[var(--text-tertiary)] mt-0.5 font-mono hover:text-[var(--text-secondary)]"
                >
                  <span>{shortenPubkey(pubkey, 12)}</span>
                  {copied ? (
                    <svg className="w-3.5 h-3.5 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <polyline points="20 6 9 17 4 12"/>
                    </svg>
                  ) : (
                    <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                      <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/>
                    </svg>
                  )}
                </button>
              </div>
            </div>
            
            {profile?.about && (
              <p className="text-sm text-[var(--text-secondary)] mt-3 whitespace-pre-wrap">
                {profile.about}
              </p>
            )}

            {/* Lightning Address */}
            {profile?.lud16 && (
              <div className="flex items-center gap-2 mt-2 text-sm text-[var(--text-tertiary)]">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
                </svg>
                <span className="truncate">{profile.lud16}</span>
              </div>
            )}

            {/* Website */}
            {rawProfile?.website && (
              <div className="flex items-center gap-2 mt-2 text-sm text-[var(--text-tertiary)]">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                  <circle cx="12" cy="12" r="10"/>
                  <line x1="2" y1="12" x2="22" y2="12"/>
                  <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
                </svg>
                <a 
                  href={rawProfile.website.startsWith('http') ? rawProfile.website : `https://${rawProfile.website}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="truncate text-[var(--line-green)] hover:underline"
                >
                  {rawProfile.website.replace(/^https?:\/\//, '')}
                </a>
              </div>
            )}

            {/* Birthday */}
            {(profile?.birthday || rawProfile?.birthday) && (
              <div className="flex items-center gap-2 mt-2 text-sm text-[var(--text-tertiary)]">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M20 21v-8a2 2 0 00-2-2H6a2 2 0 00-2 2v8"/>
                  <path d="M4 16s.5-1 2-1 2.5 2 4 2 2.5-2 4-2 2.5 2 4 2 2-1 2-1"/>
                  <path d="M2 21h20"/>
                  <path d="M7 8v2"/>
                  <path d="M12 8v2"/>
                  <path d="M17 8v2"/>
                  <path d="M7 4h.01"/>
                  <path d="M12 4h.01"/>
                  <path d="M17 4h.01"/>
                </svg>
                <span>{formatBirthday(profile?.birthday || rawProfile?.birthday)}</span>
              </div>
            )}

            {/* Profile Badges */}
            {pubkey && (
              <div className="flex items-center gap-2 mt-3">
                <BadgeDisplay pubkey={pubkey} maxBadges={3} />
              </div>
            )}

            {/* Follow count */}
            <button
              onClick={() => {
                setShowFollowList(true)
                if (followList.length > 0 && Object.keys(followProfiles).length === 0) {
                  loadFollowList()
                }
              }}
              className="flex items-center gap-2 mt-3 text-sm text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors"
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/>
                <circle cx="9" cy="7" r="4"/>
                <path d="M23 21v-2a4 4 0 00-3-3.87"/>
                <path d="M16 3.13a4 4 0 010 7.75"/>
              </svg>
              <span className="font-medium">{followList.length}</span>
              <span>フォロー中</span>
            </button>
          </div>
        </div>
      </div>

      {/* Follow List Modal */}
      {showFollowList && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center modal-overlay" onClick={() => setShowFollowList(false)}>
          <div 
            className="w-full max-h-[80vh] sm:max-h-[70vh] sm:max-w-md bg-[var(--bg-primary)] rounded-t-2xl sm:rounded-2xl flex flex-col overflow-hidden animate-scaleIn mb-16 sm:mb-0"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)] flex-shrink-0">
              <h3 className="text-lg font-bold text-[var(--text-primary)]">フォロー中 ({followList.length})</h3>
              <button onClick={() => setShowFollowList(false)} className="text-[var(--text-tertiary)] action-btn p-1">
                <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18"/>
                  <line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
            
            <div className="flex-1 overflow-y-auto">
              {followListLoading ? (
                <div className="p-8 text-center">
                  <div className="w-8 h-8 border-2 border-[var(--line-green)] border-t-transparent rounded-full animate-spin mx-auto mb-3"/>
                  <p className="text-sm text-[var(--text-tertiary)]">読み込み中...</p>
                </div>
              ) : followList.length === 0 ? (
                <div className="p-8 text-center text-[var(--text-tertiary)]">
                  <p className="text-sm">フォローしているユーザーはいません</p>
                </div>
              ) : (
                <div className="divide-y divide-[var(--border-color)]">
                  {followList.map(pk => {
                    const p = followProfiles[pk]
                    return (
                      <div
                        key={pk}
                        className="flex items-center gap-3 p-4 hover:bg-[var(--bg-secondary)] transition-colors"
                      >
                        <button
                          onClick={() => {
                            setShowFollowList(false)
                            setViewingProfile(pk)
                          }}
                          className="flex items-center gap-3 flex-1 min-w-0 text-left"
                        >
                          <img
                            src={p?.picture || `https://api.dicebear.com/7.x/identicon/svg?seed=${pk}`}
                            alt=""
                            className="w-10 h-10 rounded-full object-cover flex-shrink-0"
                            onError={(e) => {
                              e.target.src = `https://api.dicebear.com/7.x/identicon/svg?seed=${pk}`
                            }}
                          />
                          <div className="flex-1 min-w-0">
                            <p className="font-medium text-[var(--text-primary)] truncate">
                              {p?.name || shortenPubkey(pk)}
                            </p>
                            {p?.nip05 && (
                              <p className="text-xs text-[var(--text-tertiary)] truncate">{p.nip05}</p>
                            )}
                          </div>
                        </button>
                        <button
                          onClick={() => handleUnfollow(pk)}
                          disabled={unfollowing === pk}
                          className="flex-shrink-0 px-3 py-1.5 text-xs rounded-full border border-red-400 text-red-400 hover:bg-red-400/10 disabled:opacity-50 transition-colors"
                        >
                          {unfollowing === pk ? '...' : '解除'}
                        </button>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Edit Profile Modal */}
      {isEditing && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center modal-overlay" onClick={() => setIsEditing(false)}>
          <div 
            className="w-full max-h-[85vh] sm:max-h-[85vh] sm:max-w-md bg-[var(--bg-primary)] rounded-t-2xl sm:rounded-2xl flex flex-col overflow-hidden animate-scaleIn mb-16 sm:mb-0"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Modal Header */}
            <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)] flex-shrink-0">
              <button onClick={() => setIsEditing(false)} className="text-[var(--text-secondary)] text-sm">
                キャンセル
              </button>
              <h3 className="text-base font-bold text-[var(--text-primary)]">プロフィール編集</h3>
              <button
                onClick={handleSaveProfile}
                className="text-[var(--line-green)] font-semibold text-sm"
              >
                保存
              </button>
            </div>
            
            {/* Modal Body - Scrollable */}
            <div className="flex-1 overflow-y-auto p-4">
              <div className="space-y-4 pb-4">
                <div>
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">名前</label>
                  <input
                    type="text"
                    value={editForm.name}
                    onChange={(e) => setEditForm({...editForm, name: e.target.value})}
                    className="input-line"
                    placeholder="表示名"
                  />
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">アイコン画像</label>
                  <div className="flex gap-2">
                    <input
                      type="url"
                      value={editForm.picture}
                      onChange={(e) => setEditForm({...editForm, picture: e.target.value})}
                      className="input-line flex-1"
                      placeholder="https://..."
                    />
                    <input
                      ref={pictureInputRef}
                      type="file"
                      accept="image/*"
                      onChange={handlePictureUpload}
                      className="hidden"
                    />
                    <button
                      type="button"
                      onClick={() => pictureInputRef.current?.click()}
                      disabled={uploadingPicture}
                      className="btn-secondary px-3 flex-shrink-0"
                    >
                      {uploadingPicture ? (
                        <div className="w-5 h-5 border-2 border-[var(--text-tertiary)] border-t-transparent rounded-full animate-spin" />
                      ) : (
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                          <polyline points="17 8 12 3 7 8"/>
                          <line x1="12" y1="3" x2="12" y2="15"/>
                        </svg>
                      )}
                    </button>
                  </div>
                  <p className="text-xs text-[var(--text-tertiary)] mt-1">URLを入力するか画像をアップロード</p>
                </div>

                <div>
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">バナー画像</label>
                  <div className="flex gap-2">
                    <input
                      type="url"
                      value={editForm.banner}
                      onChange={(e) => setEditForm({...editForm, banner: e.target.value})}
                      className="input-line flex-1"
                      placeholder="https://..."
                    />
                    <input
                      ref={bannerInputRef}
                      type="file"
                      accept="image/*"
                      onChange={handleBannerUpload}
                      className="hidden"
                    />
                    <button
                      type="button"
                      onClick={() => bannerInputRef.current?.click()}
                      disabled={uploadingBanner}
                      className="btn-secondary px-3 flex-shrink-0"
                    >
                      {uploadingBanner ? (
                        <div className="w-5 h-5 border-2 border-[var(--text-tertiary)] border-t-transparent rounded-full animate-spin" />
                      ) : (
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                          <polyline points="17 8 12 3 7 8"/>
                          <line x1="12" y1="3" x2="12" y2="15"/>
                        </svg>
                      )}
                    </button>
                  </div>
                  <p className="text-xs text-[var(--text-tertiary)] mt-1">URLを入力するか画像をアップロード</p>
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">自己紹介</label>
                  <textarea
                    value={editForm.about}
                    onChange={(e) => setEditForm({...editForm, about: e.target.value})}
                    className="input-line resize-none h-24"
                    placeholder="自己紹介"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">NIP-05</label>
                  <input
                    type="text"
                    value={editForm.nip05}
                    onChange={(e) => setEditForm({...editForm, nip05: e.target.value})}
                    className="input-line"
                    placeholder="name@example.com"
                  />
                  <p className="text-xs text-[var(--text-tertiary)] mt-1">認証済みアドレス</p>
                </div>

                <div>
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">ライトニングアドレス</label>
                  <input
                    type="text"
                    value={editForm.lud16}
                    onChange={(e) => setEditForm({...editForm, lud16: e.target.value})}
                    className="input-line"
                    placeholder="you@wallet.com"
                  />
                  <p className="text-xs text-[var(--text-tertiary)] mt-1">Zap受け取り用アドレス</p>
                </div>

                <div>
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">ウェブサイト</label>
                  <input
                    type="url"
                    value={editForm.website}
                    onChange={(e) => setEditForm({...editForm, website: e.target.value})}
                    className="input-line"
                    placeholder="https://example.com"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">誕生日</label>
                  <input
                    type="text"
                    value={editForm.birthday}
                    onChange={(e) => setEditForm({...editForm, birthday: e.target.value})}
                    className="input-line"
                    placeholder="MM-DD または YYYY-MM-DD"
                  />
                  <p className="text-xs text-[var(--text-tertiary)] mt-1">例: 01-15 または 2000-01-15</p>
                </div>
              </div>
            </div>
          </div>
        </div>
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

              {/* Textarea with preview overlay */}
              <div className="relative min-h-[120px] sm:min-h-[150px]">
                <textarea
                  value={newPost}
                  onChange={(e) => setNewPost(e.target.value)}
                  spellCheck={false}
                  className={`w-full min-h-[120px] sm:min-h-[150px] bg-transparent resize-none placeholder-[var(--text-tertiary)] outline-none text-base ${
                    newPost && (newPost.includes('#') || emojiTags.length > 0)
                      ? 'text-transparent caret-[var(--text-primary)] absolute inset-0 z-10'
                      : 'text-[var(--text-primary)] relative'
                  }`}
                  placeholder="いまどうしてる？"
                  autoFocus
                />
                {/* Visible preview layer - only show when there are hashtags or emojis */}
                {newPost && (newPost.includes('#') || emojiTags.length > 0) && (
                  <div className="w-full min-h-[120px] sm:min-h-[150px] pointer-events-none">
                    <ContentPreview content={newPost} customEmojis={emojiTags} />
                  </div>
                )}
              </div>

              {/* Image preview */}
              {postImage && (
                <div className="relative mt-3 inline-block flex-shrink-0">
                  <img src={postImage} alt="Upload preview" className="max-h-48 max-w-full object-contain rounded-xl" />
                  <button
                    onClick={() => setPostImage(null)}
                    className="absolute top-2 right-2 w-8 h-8 bg-black/60 rounded-full flex items-center justify-center text-white hover:bg-black/80 transition-colors"
                  >
                    <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <line x1="18" y1="6" x2="6" y2="18"/>
                      <line x1="6" y1="6" x2="18" y2="18"/>
                    </svg>
                  </button>
                </div>
              )}

              {/* Image upload, CW, and emoji picker buttons */}
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

                  {/* Content Warning toggle (NIP-36) */}
                  <button
                    onClick={() => setShowCWInput(!showCWInput)}
                    className={`flex items-center gap-2 text-sm ${showCWInput ? 'text-orange-500' : 'text-[var(--text-tertiary)]'}`}
                    title="コンテンツ警告 (CW)"
                  >
                    <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                      <line x1="12" y1="9" x2="12" y2="13"/>
                      <line x1="12" y1="17" x2="12.01" y2="17"/>
                    </svg>
                    CW
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

      {/* FAB - Post Button */}
      <button
        onClick={() => setShowPostModal(true)}
        className="fab"
      >
        <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <line x1="12" y1="5" x2="12" y2="19"/>
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
      </button>

      {/* Posts/Likes Section */}
      <div className="mt-4">
        {/* Section Tabs */}
        <div className="flex border-b border-[var(--border-color)]">
          <button
            onClick={() => setActiveSection('posts')}
            className={`flex-1 py-3 text-sm font-medium text-center transition-colors ${
              activeSection === 'posts' 
                ? 'text-[var(--line-green)] border-b-2 border-[var(--line-green)]' 
                : 'text-[var(--text-tertiary)]'
            }`}
          >
            投稿 ({posts.length})
          </button>
          <button
            onClick={() => setActiveSection('likes')}
            className={`flex-1 py-3 text-sm font-medium text-center transition-colors ${
              activeSection === 'likes' 
                ? 'text-[var(--line-green)] border-b-2 border-[var(--line-green)]' 
                : 'text-[var(--text-tertiary)]'
            }`}
          >
            いいね ({likedPosts.length})
          </button>
        </div>
        
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
        ) : activeSection === 'posts' ? (
          posts.length === 0 ? (
            <div className="px-4 py-12 text-center">
              <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
                <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/>
                </svg>
              </div>
              <p className="text-[var(--text-secondary)]">投稿がありません</p>
            </div>
          ) : (
            <div className="divide-y divide-[var(--border-color)]">
              {posts.map((post, index) => {
                const postProfile = post._isRepost ? profiles[post.pubkey] : profile
                const likeCount = reactions[post.id] || 0
                const hasLiked = userReactions.has(post.id)
                const hasReposted = userReposts.has(post.id)
                const isLiking = likeAnimating === post.id
                const isZapping = zapAnimating === post.id

                return (
                  <div 
                    key={post._repostId || post.id} 
                    className="animate-fadeIn"
                    style={{ animationDelay: `${Math.min(index * 30, 300)}ms` }}
                  >
                    <PostItem
                      post={post}
                      profile={postProfile}
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
                      onHashtagClick={onHashtagClick}
                      onDelete={handleDelete}
                      isOwnPost={post.pubkey === pubkey}
                      onAvatarClick={(targetPubkey) => {
                        if (targetPubkey !== pubkey) {
                          setViewingProfile(targetPubkey)
                        }
                      }}
                      isRepost={post._isRepost}
                      repostedBy={post._repostedBy ? profiles[post._repostedBy] || { pubkey: post._repostedBy, name: profile?.name } : null}
                    />
                  </div>
                )
              })}
            </div>
          )
        ) : (
          /* Likes section */
          likedPosts.length === 0 ? (
            <div className="px-4 py-12 text-center">
              <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
                <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="M20.84 4.61a5.5 5.5 0 00-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 00-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 000-7.78z"/>
                </svg>
              </div>
              <p className="text-[var(--text-secondary)]">いいねがありません</p>
            </div>
          ) : (
            <div className="divide-y divide-[var(--border-color)]">
              {likedPosts.map((post, index) => {
                const postProfile = profiles[post.pubkey]
                const likeCount = reactions[post.id] || 0
                const hasLiked = userReactions.has(post.id)
                const hasReposted = userReposts.has(post.id)
                const isLiking = likeAnimating === post.id
                const isZapping = zapAnimating === post.id

                return (
                  <div 
                    key={post.id} 
                    className="animate-fadeIn"
                    style={{ animationDelay: `${Math.min(index * 30, 300)}ms` }}
                  >
                    <PostItem
                      post={post}
                      profile={postProfile}
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
                      onHashtagClick={onHashtagClick}
                      onAvatarClick={(targetPubkey) => {
                        if (targetPubkey !== pubkey) {
                          setViewingProfile(targetPubkey)
                        }
                      }}
                    />
                  </div>
                )
              })}
            </div>
          )
        )}
      </div>

      {/* User Profile View */}
      {viewingProfile && (
        <UserProfileView
          targetPubkey={viewingProfile}
          myPubkey={pubkey}
          onClose={() => setViewingProfile(null)}
          onStartDM={(targetPubkey) => {
            setViewingProfile(null)
            if (onStartDM) onStartDM(targetPubkey)
          }}
        />
      )}
    </div>
  )
})

export default HomeTab
