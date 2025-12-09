'use client'

import { useState, useEffect, useRef } from 'react'
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
  DEFAULT_RELAY,
  RELAYS
} from '@/lib/nostr'
import PostItem from './PostItem'
import UserProfileView from './UserProfileView'

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

  const display = nip05.startsWith('_@') ? nip05.slice(1) : nip05

  return (
    <div className="flex items-center gap-1 text-sm text-[var(--line-green)] mt-1">
      <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
        <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/>
      </svg>
      <span>{display}</span>
    </div>
  )
}

export default function HomeTab({ pubkey, onLogout }) {
  const [profile, setProfile] = useState(null)
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
    lud16: ''
  })
  const [showPostModal, setShowPostModal] = useState(false)
  const [newPost, setNewPost] = useState('')
  const [posting, setPosting] = useState(false)
  const [reactions, setReactions] = useState({})
  const [userReactions, setUserReactions] = useState(new Set())
  const [userReposts, setUserReposts] = useState(new Set())
  const [userReactionIds, setUserReactionIds] = useState({}) // eventId -> reactionEventId
  const [userRepostIds, setUserRepostIds] = useState({}) // eventId -> repostEventId
  const [likeAnimating, setLikeAnimating] = useState(null)
  const [zapAnimating, setZapAnimating] = useState(null)
  const [copied, setCopied] = useState(false)
  const [viewingProfile, setViewingProfile] = useState(null)
  const [uploadingPicture, setUploadingPicture] = useState(false)
  const [uploadingBanner, setUploadingBanner] = useState(false)
  const pictureInputRef = useRef(null)
  const bannerInputRef = useRef(null)

  useEffect(() => {
    if (pubkey) {
      loadProfile()
      loadPosts()
    }
  }, [pubkey])

  // Lock body scroll when modal is open
  useEffect(() => {
    if (isEditing || showPostModal || viewingProfile) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [isEditing, showPostModal, viewingProfile])

  const loadProfile = async () => {
    if (!pubkey) return
    
    try {
      const events = await fetchEvents(
        { kinds: [0], authors: [pubkey], limit: 1 },
        RELAYS
      )
      
      if (events.length > 0) {
        const p = parseProfile(events[0])
        setProfile(p)
        setProfiles(prev => ({ ...prev, [pubkey]: p }))
        setEditForm({
          name: p?.name || '',
          about: p?.about || '',
          picture: p?.picture || '',
          banner: p?.banner || '',
          nip05: p?.nip05 || '',
          lud16: p?.lud16 || ''
        })
      }
    } catch (e) {
      console.error('Failed to load profile:', e)
    }
  }

  const loadPosts = async () => {
    if (!pubkey) return
    setLoading(true)
    
    try {
      // Fetch notes and reposts in parallel
      const [notes, reposts] = await Promise.all([
        fetchEvents({ kinds: [1], authors: [pubkey], limit: 30 }, RELAYS),
        fetchEvents({ kinds: [6], authors: [pubkey], limit: 20 }, RELAYS)
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

      // Fetch profiles for original authors
      if (originalAuthors.size > 0) {
        const authorProfiles = await fetchEvents(
          { kinds: [0], authors: Array.from(originalAuthors) },
          RELAYS
        )
        
        const newProfiles = { ...profiles }
        for (const event of authorProfiles) {
          const p = parseProfile(event)
          if (p) newProfiles[event.pubkey] = p
        }
        setProfiles(newProfiles)
      }

      // Combine and sort by time
      const allPosts = [...notes, ...repostData].sort((a, b) => {
        const timeA = a._repostTime || a.created_at
        const timeB = b._repostTime || b.created_at
        return timeB - timeA
      })

      setPosts(allPosts)

      // Fetch reactions
      const postIds = allPosts.map(p => p.id)
      if (postIds.length > 0) {
        const reactionEvents = await fetchEvents(
          { kinds: [7], '#e': postIds, limit: 500 },
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
      const profileData = {
        name: editForm.name,
        display_name: editForm.name,
        about: editForm.about,
        picture: editForm.picture
      }
      
      // Only add optional fields if they have values
      if (editForm.banner) profileData.banner = editForm.banner
      if (editForm.nip05) profileData.nip05 = editForm.nip05
      if (editForm.lud16) profileData.lud16 = editForm.lud16
      
      const event = createEventTemplate(0, JSON.stringify(profileData))
      event.pubkey = pubkey
      
      const signedEvent = await signEventNip07(event)
      const success = await publishEvent(signedEvent)
      
      if (success) {
        const newProfile = {
          ...profile,
          name: editForm.name,
          displayName: editForm.name,
          about: editForm.about,
          picture: editForm.picture,
          banner: editForm.banner,
          nip05: editForm.nip05,
          lud16: editForm.lud16
        }
        setProfile(newProfile)
        setProfiles(prev => ({ ...prev, [pubkey]: newProfile }))
        setIsEditing(false)
      }
    } catch (e) {
      console.error('Failed to save profile:', e)
      alert('プロフィールの保存に失敗しました')
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

  const handlePost = async () => {
    if (!newPost.trim()) return
    setPosting(true)
    
    try {
      const event = createEventTemplate(1, newPost.trim())
      event.pubkey = pubkey
      
      const signedEvent = await signEventNip07(event)
      const success = await publishEvent(signedEvent)
      
      if (success) {
        setPosts([signedEvent, ...posts])
        setNewPost('')
        setShowPostModal(false)
      }
    } catch (e) {
      console.error('Failed to post:', e)
      alert('投稿に失敗しました')
    } finally {
      setPosting(false)
    }
  }

  const handleLike = async (event) => {
    if (!pubkey || !hasNip07() || userReactions.has(event.id)) return

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
    }
  }

  const handleUnlike = async (event, reactionEventId) => {
    if (!pubkey || !hasNip07() || !userReactions.has(event.id)) return

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
    if (!pubkey || !hasNip07() || userReposts.has(event.id)) return

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
    }
  }

  const handleUnrepost = async (event, repostEventId) => {
    if (!pubkey || !hasNip07() || !userReposts.has(event.id)) return

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
    <div className="min-h-screen pb-16">
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
          </div>
        </div>
      </div>

      {/* Edit Profile Modal */}
      {isEditing && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center modal-overlay" onClick={() => setIsEditing(false)}>
          <div 
            className="w-full max-h-[80vh] sm:max-h-[85vh] sm:max-w-md bg-[var(--bg-primary)] rounded-t-2xl sm:rounded-2xl flex flex-col overflow-hidden animate-scaleIn"
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
              <div className="space-y-4 pb-8">
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
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Post Modal */}
      {showPostModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowPostModal(false)}>
          <div 
            className="w-full h-full sm:h-auto sm:max-w-lg bg-[var(--bg-primary)] sm:rounded-2xl flex flex-col overflow-hidden animate-scaleIn"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)]">
              <button onClick={() => setShowPostModal(false)} className="text-[var(--text-secondary)] action-btn">
                キャンセル
              </button>
              <span className="font-semibold text-[var(--text-primary)]">新規投稿</span>
              <button
                onClick={handlePost}
                disabled={posting || !newPost.trim()}
                className="btn-line text-sm py-1.5 px-4 disabled:opacity-50"
              >
                {posting ? '...' : '投稿'}
              </button>
            </div>
            <div className="flex-1 p-4">
              <textarea
                value={newPost}
                onChange={(e) => setNewPost(e.target.value)}
                className="w-full h-full min-h-[200px] bg-transparent resize-none text-[var(--text-primary)] placeholder-[var(--text-tertiary)] outline-none text-base"
                placeholder="いまどうしてる？"
                autoFocus
              />
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

      {/* Posts Section */}
      <div className="mt-4">
        <div className="px-4 py-2">
          <h3 className="text-sm font-semibold text-[var(--text-secondary)]">投稿</h3>
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
        ) : posts.length === 0 ? (
          <div className="px-4 py-12 text-center">
            <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
              <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/>
              </svg>
            </div>
            <p className="text-[var(--text-secondary)]">まだ投稿がありません</p>
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
        )}
      </div>

      {/* User Profile View */}
      {viewingProfile && (
        <UserProfileView
          targetPubkey={viewingProfile}
          myPubkey={pubkey}
          onClose={() => setViewingProfile(null)}
        />
      )}
    </div>
  )
}
