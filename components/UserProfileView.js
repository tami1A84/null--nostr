'use client'

import { useState, useEffect } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchEvents,
  parseProfile,
  shortenPubkey,
  formatTimestamp,
  verifyNip05,
  encodeNpub,
  addToMuteList,
  signEventNip07,
  createEventTemplate,
  publishEvent,
  hasNip07,
  parseNostrLink,
  RELAYS
} from '@/lib/nostr'
import PostItem from './PostItem'

// Render text with links
function RenderLinkedContent({ content }) {
  if (!content) return null
  
  // Regex for URLs and nostr: links
  const combinedRegex = /(https?:\/\/[^\s]+|nostr:(?:note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]+)/gi
  
  const parts = content.split(combinedRegex).filter(Boolean)
  
  return (
    <>
      {parts.map((part, i) => {
        // Check for nostr: links
        if (part.startsWith('nostr:')) {
          const bech32 = part.slice(6)
          const parsed = parseNostrLink(bech32)
          
          if (parsed) {
            if (parsed.type === 'npub' || parsed.type === 'nprofile') {
              return (
                <span key={i} className="text-[var(--line-green)]">
                  @{shortenPubkey(parsed.pubkey, 8)}
                </span>
              )
            }
            if (parsed.type === 'note' || parsed.type === 'nevent') {
              return (
                <span key={i} className="text-[var(--line-green)]">
                  📝{bech32.slice(0, 12)}...
                </span>
              )
            }
          }
          return <span key={i} className="text-[var(--line-green)]">{part}</span>
        }
        
        // Check for regular URLs
        if (part.match(/^https?:\/\//)) {
          return (
            <a 
              key={i} 
              href={part} 
              target="_blank" 
              rel="noopener noreferrer"
              className="text-[var(--line-green)] hover:underline break-all"
            >
              {part.length > 40 ? part.slice(0, 40) + '...' : part}
            </a>
          )
        }
        
        return <span key={i}>{part}</span>
      })}
    </>
  )
}

// NIP-05 Badge
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
    <div className="flex items-center gap-1 text-sm text-[var(--line-green)] mt-1 min-w-0">
      <svg className="w-4 h-4 flex-shrink-0" viewBox="0 0 24 24" fill="currentColor">
        <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z"/>
      </svg>
      <span className="truncate">{display}</span>
    </div>
  )
}

export default function UserProfileView({ 
  targetPubkey, 
  myPubkey,
  onClose,
  onMute 
}) {
  const [profile, setProfile] = useState(null)
  const [posts, setPosts] = useState([])
  const [profiles, setProfiles] = useState({})
  const [loading, setLoading] = useState(true)
  const [reactions, setReactions] = useState({})
  const [userReactions, setUserReactions] = useState(new Set())
  const [userReposts, setUserReposts] = useState(new Set())
  const [copied, setCopied] = useState(false)
  const [showMenu, setShowMenu] = useState(false)
  const [muting, setMuting] = useState(false)
  const [likeAnimating, setLikeAnimating] = useState(null)
  const [zapAnimating, setZapAnimating] = useState(null)

  useEffect(() => {
    if (targetPubkey) {
      loadUserData()
    }
  }, [targetPubkey])

  // Lock body scroll
  useEffect(() => {
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = ''
    }
  }, [])

  const loadUserData = async () => {
    setLoading(true)
    
    try {
      // Fetch profile
      const profileEvents = await fetchEvents(
        { kinds: [0], authors: [targetPubkey], limit: 1 },
        RELAYS
      )
      
      if (profileEvents.length > 0) {
        const p = parseProfile(profileEvents[0])
        setProfile(p)
        setProfiles(prev => ({ ...prev, [targetPubkey]: p }))
      }

      // Fetch posts
      const noteEvents = await fetchEvents(
        { kinds: [1], authors: [targetPubkey], limit: 30 },
        RELAYS
      )
      setPosts(noteEvents)

      // Fetch reactions
      if (noteEvents.length > 0 && myPubkey) {
        const eventIds = noteEvents.map(e => e.id)
        const reactionEvents = await fetchEvents(
          { kinds: [7], '#e': eventIds, limit: 500 },
          RELAYS
        )

        const counts = {}
        const myReactions = new Set()
        for (const event of reactionEvents) {
          const targetId = event.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) {
            counts[targetId] = (counts[targetId] || 0) + 1
            if (event.pubkey === myPubkey) {
              myReactions.add(targetId)
            }
          }
        }
        setReactions(counts)
        setUserReactions(myReactions)

        // Fetch user's reposts
        const myReposts = await fetchEvents(
          { kinds: [6], authors: [myPubkey], '#e': eventIds, limit: 100 },
          RELAYS
        )
        const repostedIds = new Set()
        for (const repost of myReposts) {
          const targetId = repost.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) repostedIds.add(targetId)
        }
        setUserReposts(repostedIds)
      }
    } catch (e) {
      console.error('Failed to load user data:', e)
    } finally {
      setLoading(false)
    }
  }

  const handleCopyPubkey = async () => {
    try {
      const npub = encodeNpub(targetPubkey)
      await navigator.clipboard.writeText(npub || targetPubkey)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch (e) {
      console.error('Failed to copy:', e)
    }
  }

  const handleMute = async () => {
    if (!myPubkey || muting) return
    setMuting(true)
    setShowMenu(false)

    try {
      await addToMuteList(myPubkey, 'pubkey', targetPubkey)
      alert('ミュートしました')
      if (onMute) onMute(targetPubkey)
      onClose()
    } catch (e) {
      console.error('Failed to mute:', e)
      alert('ミュートに失敗しました')
    } finally {
      setMuting(false)
    }
  }

  const handleLike = async (event) => {
    if (!myPubkey || !hasNip07() || userReactions.has(event.id)) return

    setLikeAnimating(event.id)
    setTimeout(() => setLikeAnimating(null), 300)

    try {
      const reactionEvent = createEventTemplate(7, '+', [
        ['e', event.id],
        ['p', event.pubkey]
      ])
      reactionEvent.pubkey = myPubkey
      
      const signed = await signEventNip07(reactionEvent)
      const success = await publishEvent(signed)

      if (success) {
        setUserReactions(prev => new Set([...prev, event.id]))
        setReactions(prev => ({
          ...prev,
          [event.id]: (prev[event.id] || 0) + 1
        }))
      }
    } catch (e) {
      console.error('Failed to like:', e)
    }
  }

  const handleRepost = async (event) => {
    if (!myPubkey || !hasNip07() || userReposts.has(event.id)) return

    try {
      const repostEvent = createEventTemplate(6, JSON.stringify(event), [
        ['e', event.id, '', 'mention'],
        ['p', event.pubkey]
      ])
      repostEvent.pubkey = myPubkey
      
      const signed = await signEventNip07(repostEvent)
      const success = await publishEvent(signed)

      if (success) {
        setUserReposts(prev => new Set([...prev, event.id]))
        alert('リポストしました')
      }
    } catch (e) {
      console.error('Failed to repost:', e)
    }
  }

  const handleZap = async (event) => {
    if (!profile?.lud16) {
      alert('この投稿者はLightningアドレスを設定していません')
      return
    }

    setZapAnimating(event.id)
    setTimeout(() => setZapAnimating(null), 600)

    alert(`⚡ Zap送信\n\n対象: ${profile.name || shortenPubkey(event.pubkey)}\nLN: ${profile.lud16}`)
  }

  const npub = encodeNpub(targetPubkey)

  return (
    <div className="fixed inset-0 z-50 bg-[var(--bg-primary)]">
      {/* Header */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center justify-between px-2 h-12">
          <button
            onClick={onClose}
            className="w-10 h-10 flex items-center justify-center action-btn"
          >
            <svg className="w-6 h-6 text-[var(--text-primary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="15 18 9 12 15 6"/>
            </svg>
          </button>
          <h1 className="text-lg font-semibold text-[var(--text-primary)]">プロフィール</h1>
          
          {/* Menu button */}
          {myPubkey && myPubkey !== targetPubkey && (
            <div className="relative">
              <button
                onClick={() => setShowMenu(!showMenu)}
                className="w-10 h-10 flex items-center justify-center action-btn"
              >
                <svg className="w-6 h-6 text-[var(--text-primary)]" viewBox="0 0 24 24" fill="currentColor">
                  <circle cx="12" cy="5" r="2"/>
                  <circle cx="12" cy="12" r="2"/>
                  <circle cx="12" cy="19" r="2"/>
                </svg>
              </button>
              
              {/* Dropdown menu */}
              {showMenu && (
                <>
                  <div 
                    className="fixed inset-0 z-40" 
                    onClick={() => setShowMenu(false)}
                  />
                  <div className="absolute right-0 top-10 z-50 bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-lg shadow-lg py-1 min-w-[140px]">
                    <button
                      onClick={handleMute}
                      disabled={muting}
                      className="w-full px-4 py-2 text-left text-sm text-red-500 hover:bg-[var(--bg-secondary)] flex items-center gap-2 disabled:opacity-50"
                    >
                      <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="10"/>
                        <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
                      </svg>
                      {muting ? 'ミュート中...' : 'ミュート'}
                    </button>
                  </div>
                </>
              )}
            </div>
          )}
          {(!myPubkey || myPubkey === targetPubkey) && <div className="w-10" />}
        </div>
      </header>

      <div className="h-[calc(100vh-48px)] overflow-y-auto pb-16">
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
                    <img src={profile.picture} alt="" className="w-full h-full object-cover" />
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
                <h2 className="text-lg font-bold text-[var(--text-primary)] truncate">
                  {profile?.name || 'Anonymous'}
                </h2>
                {profile?.nip05 && (
                  <ProfileNip05Badge nip05={profile.nip05} pubkey={targetPubkey} />
                )}
                <button
                  onClick={handleCopyPubkey}
                  className="flex items-center gap-1 text-xs text-[var(--text-tertiary)] mt-0.5 font-mono hover:text-[var(--text-secondary)]"
                >
                  <span>{shortenPubkey(targetPubkey, 12)}</span>
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
              <p className="text-sm text-[var(--text-secondary)] mt-3 whitespace-pre-wrap break-words">
                <RenderLinkedContent content={profile.about} />
              </p>
            )}

            {/* Lightning Address */}
            {profile?.lud16 && (
              <div className="flex items-center gap-2 mt-3 text-sm text-[var(--text-tertiary)]">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
                </svg>
                <span className="truncate">{profile.lud16}</span>
              </div>
            )}
          </div>
        </div>

        {/* Posts */}
        <div className="mt-4">
          <h3 className="px-4 py-2 text-sm font-semibold text-[var(--text-secondary)] border-b border-[var(--border-color)]">
            投稿
          </h3>
          
          {loading ? (
            <div className="px-4 py-8 text-center text-[var(--text-tertiary)]">
              読み込み中...
            </div>
          ) : posts.length === 0 ? (
            <div className="px-4 py-8 text-center text-[var(--text-tertiary)]">
              投稿がありません
            </div>
          ) : (
            <div className="divide-y divide-[var(--border-color)]">
              {posts.map(post => (
                <PostItem
                  key={post.id}
                  post={post}
                  profile={profile}
                  profiles={profiles}
                  likeCount={reactions[post.id] || 0}
                  hasLiked={userReactions.has(post.id)}
                  hasReposted={userReposts.has(post.id)}
                  isLiking={likeAnimating === post.id}
                  isZapping={zapAnimating === post.id}
                  onLike={handleLike}
                  onRepost={handleRepost}
                  onZap={handleZap}
                  showActions={true}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
