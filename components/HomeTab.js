'use client'

import { useState, useEffect } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchEvents,
  parseProfile,
  signEventNip07,
  createEventTemplate,
  publishEvent,
  shortenPubkey,
  formatTimestamp,
  DEFAULT_RELAY,
  RELAYS
} from '@/lib/nostr'

export default function HomeTab({ pubkey, onLogout }) {
  const [profile, setProfile] = useState(null)
  const [posts, setPosts] = useState([])
  const [loading, setLoading] = useState(true)
  const [isEditing, setIsEditing] = useState(false)
  const [editForm, setEditForm] = useState({
    name: '',
    about: '',
    picture: ''
  })
  const [showPostModal, setShowPostModal] = useState(false)
  const [newPost, setNewPost] = useState('')
  const [posting, setPosting] = useState(false)

  useEffect(() => {
    if (pubkey) {
      loadProfile()
      loadPosts()
    }
  }, [pubkey])

  // Lock body scroll when modal is open
  useEffect(() => {
    if (isEditing || showPostModal) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [isEditing, showPostModal])

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
        setEditForm({
          name: p?.name || '',
          about: p?.about || '',
          picture: p?.picture || ''
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
      const events = await fetchEvents(
        { kinds: [1], authors: [pubkey], limit: 30 },
        RELAYS
      )
      setPosts(events)
    } catch (e) {
      console.error('Failed to load posts:', e)
    } finally {
      setLoading(false)
    }
  }

  const handleSaveProfile = async () => {
    try {
      const event = createEventTemplate(0, JSON.stringify({
        name: editForm.name,
        display_name: editForm.name,
        about: editForm.about,
        picture: editForm.picture
      }))
      event.pubkey = pubkey
      
      const signedEvent = await signEventNip07(event)
      const success = await publishEvent(signedEvent)
      
      if (success) {
        setProfile({
          ...profile,
          name: editForm.name,
          displayName: editForm.name,
          about: editForm.about,
          picture: editForm.picture
        })
        setIsEditing(false)
      }
    } catch (e) {
      console.error('Failed to save profile:', e)
      alert('プロフィールの保存に失敗しました')
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
                <p className="text-xs text-[var(--text-tertiary)] mt-0.5 font-mono">
                  {shortenPubkey(pubkey, 12)}
                </p>
              </div>
            </div>
            
            {profile?.about && (
              <p className="text-sm text-[var(--text-secondary)] mt-3 whitespace-pre-wrap">
                {profile.about}
              </p>
            )}
          </div>
        </div>
      </div>

      {/* Edit Profile Modal */}
      {isEditing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setIsEditing(false)}>
          <div 
            className="w-full h-full sm:h-auto sm:max-h-[90vh] sm:max-w-md bg-[var(--bg-primary)] sm:rounded-2xl flex flex-col overflow-hidden animate-scaleIn"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Modal Header */}
            <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)] flex-shrink-0">
              <h3 className="text-lg font-bold text-[var(--text-primary)]">プロフィール編集</h3>
              <button onClick={() => setIsEditing(false)} className="text-[var(--text-tertiary)] action-btn p-1">
                <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="18" y1="6" x2="6" y2="18"/>
                  <line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
            
            {/* Modal Body - Scrollable */}
            <div className="flex-1 overflow-y-auto p-4">
              <div className="space-y-4">
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
                  <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">アイコンURL</label>
                  <input
                    type="url"
                    value={editForm.picture}
                    onChange={(e) => setEditForm({...editForm, picture: e.target.value})}
                    className="input-line"
                    placeholder="https://..."
                  />
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
              </div>
            </div>
            
            {/* Modal Footer */}
            <div className="flex gap-3 p-4 border-t border-[var(--border-color)] flex-shrink-0">
              <button
                onClick={() => setIsEditing(false)}
                className="flex-1 btn-secondary"
              >
                キャンセル
              </button>
              <button
                onClick={handleSaveProfile}
                className="flex-1 btn-line"
              >
                保存
              </button>
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
          <div className="px-4 space-y-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="bg-[var(--bg-secondary)] rounded-xl p-4">
                <div className="skeleton h-4 w-3/4 rounded mb-2" />
                <div className="skeleton h-3 w-1/4 rounded" />
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
            {posts.map((post, index) => (
              <div 
                key={post.id} 
                className="px-4 py-3 animate-fadeIn"
                style={{ animationDelay: `${index * 30}ms` }}
              >
                <p className="text-[var(--text-primary)] text-sm whitespace-pre-wrap break-words">
                  {post.content}
                </p>
                <p className="text-xs text-[var(--text-tertiary)] mt-2">
                  {formatTimestamp(post.created_at)}
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
