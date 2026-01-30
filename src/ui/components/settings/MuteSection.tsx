'use client'

import { useState, useEffect } from 'react'
import {
  fetchMuteList,
  removeFromMuteList,
  fetchEvents,
  parseProfile,
  shortenPubkey,
  getDefaultRelay
} from '@/lib/nostr'

interface MuteSectionProps {
  pubkey: string | null
  expanded?: boolean
  onToggle?: () => void
}

interface MuteList {
  pubkeys: string[]
  eventIds: string[]
  hashtags: string[]
  words: string[]
}

interface Profile {
  name?: string
  picture?: string
  [key: string]: any
}

export default function MuteSection({ pubkey, expanded = false, onToggle }: MuteSectionProps) {
  const [showSettings, setShowSettings] = useState(expanded)
  const [muteList, setMuteList] = useState<MuteList>({ pubkeys: [], eventIds: [], hashtags: [], words: [] })
  const [mutedProfiles, setMutedProfiles] = useState<Record<string, Profile>>({})
  const [loading, setLoading] = useState(true)
  const [removing, setRemoving] = useState<string | null>(null)

  useEffect(() => {
    if (pubkey) {
      loadMuteList()
    } else {
      setLoading(false)
    }
  }, [pubkey])

  const loadMuteList = async () => {
    if (!pubkey) return
    setLoading(true)
    try {
      const list = await fetchMuteList(pubkey)
      setMuteList(list)

      if (list.pubkeys.length > 0) {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: list.pubkeys, limit: list.pubkeys.length },
          [getDefaultRelay()]
        ) as { pubkey: string; [key: string]: any }[]
        const profiles: Record<string, Profile> = {}
        for (const event of profileEvents) {
          const profile = parseProfile(event)
          if (profile) {
            profiles[event.pubkey] = profile
          }
        }
        setMutedProfiles(profiles)
      }
    } catch (e) {
      console.error('Failed to load mute list:', e)
    } finally {
      setLoading(false)
    }
  }

  const handleUnmute = async (type: 'pubkey' | 'hashtag' | 'word', value: string) => {
    if (!pubkey || removing) return
    setRemoving(value)

    try {
      await removeFromMuteList(pubkey, type, value)
      if (type === 'pubkey') {
        setMuteList(prev => ({
          ...prev,
          pubkeys: prev.pubkeys.filter(p => p !== value)
        }))
      } else if (type === 'hashtag') {
        setMuteList(prev => ({
          ...prev,
          hashtags: prev.hashtags.filter(h => h !== value)
        }))
      } else if (type === 'word') {
        setMuteList(prev => ({
          ...prev,
          words: prev.words.filter(w => w !== value)
        }))
      }
    } catch (e) {
      console.error('Failed to unmute:', e)
    } finally {
      setRemoving(null)
    }
  }

  const handleToggle = () => {
    setShowSettings(!showSettings)
    onToggle?.()
  }

  const totalMuted = muteList.pubkeys.length + muteList.hashtags.length + muteList.words.length

  return (
    <section id="mute-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
      <button
        onClick={handleToggle}
        className="w-full flex items-center justify-between"
      >
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <circle cx="12" cy="12" r="10"/>
            <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
          </svg>
          <h2 className="font-semibold text-[var(--text-primary)]">ミュートリスト</h2>
          {totalMuted > 0 && (
            <span className="text-sm text-[var(--text-tertiary)]">
              ({totalMuted}件)
            </span>
          )}
        </div>
        <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </button>

      {showSettings && (
        <div className="mt-4">
          {loading ? (
            <div className="py-8 text-center text-[var(--text-tertiary)]">
              読み込み中...
            </div>
          ) : (
            <div className="space-y-4">
              {/* Muted Users */}
              {muteList.pubkeys.length > 0 && (
                <div>
                  <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ミュートしたユーザー</h3>
                  <div className="space-y-2">
                    {muteList.pubkeys.map(pk => {
                      const profile = mutedProfiles[pk]
                      return (
                        <div key={pk} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                          <div className="flex items-center gap-2 min-w-0">
                            <div className="w-8 h-8 rounded-full overflow-hidden bg-[var(--bg-primary)] flex-shrink-0">
                              {profile?.picture ? (
                                <img
                                  src={profile.picture}
                                  alt=""
                                  className="w-full h-full object-cover"
                                  referrerPolicy="no-referrer"
                                  onError={(e) => {
                                    const target = e.target as HTMLImageElement
                                    target.style.display = 'none'
                                    if (target.parentElement) {
                                      target.parentElement.innerHTML = '<div class="w-full h-full flex items-center justify-center"><svg class="w-4 h-4 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg></div>'
                                    }
                                  }}
                                />
                              ) : (
                                <div className="w-full h-full flex items-center justify-center">
                                  <svg className="w-4 h-4 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                                    <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                                  </svg>
                                </div>
                              )}
                            </div>
                            <span className="text-sm text-[var(--text-primary)] truncate">
                              {profile?.name || shortenPubkey(pk, 8)}
                            </span>
                          </div>
                          <button
                            onClick={() => handleUnmute('pubkey', pk)}
                            disabled={removing === pk}
                            className="text-xs text-red-400 hover:underline disabled:opacity-50 px-2"
                          >
                            {removing === pk ? '...' : '解除'}
                          </button>
                        </div>
                      )
                    })}
                  </div>
                </div>
              )}

              {/* Muted Hashtags */}
              {muteList.hashtags.length > 0 && (
                <div>
                  <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ミュートしたハッシュタグ</h3>
                  <div className="flex flex-wrap gap-2">
                    {muteList.hashtags.map(tag => (
                      <div key={tag} className="flex items-center gap-1 px-3 py-1.5 bg-[var(--bg-tertiary)] rounded-full">
                        <span className="text-sm text-[var(--text-primary)]">#{tag}</span>
                        <button
                          onClick={() => handleUnmute('hashtag', tag)}
                          disabled={removing === tag}
                          className="text-red-400 hover:text-red-500 disabled:opacity-50 ml-1"
                        >
                          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <line x1="18" y1="6" x2="6" y2="18"/>
                            <line x1="6" y1="6" x2="18" y2="18"/>
                          </svg>
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Muted Words */}
              {muteList.words.length > 0 && (
                <div>
                  <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ミュートしたワード</h3>
                  <div className="flex flex-wrap gap-2">
                    {muteList.words.map(word => (
                      <div key={word} className="flex items-center gap-1 px-3 py-1.5 bg-[var(--bg-tertiary)] rounded-full">
                        <span className="text-sm text-[var(--text-primary)]">{word}</span>
                        <button
                          onClick={() => handleUnmute('word', word)}
                          disabled={removing === word}
                          className="text-red-400 hover:text-red-500 disabled:opacity-50 ml-1"
                        >
                          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <line x1="18" y1="6" x2="6" y2="18"/>
                            <line x1="6" y1="6" x2="18" y2="18"/>
                          </svg>
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Empty state */}
              {totalMuted === 0 && (
                <div className="py-6 text-center text-[var(--text-tertiary)]">
                  <p className="text-sm">ミュートしているユーザーやワードはありません</p>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </section>
  )
}
