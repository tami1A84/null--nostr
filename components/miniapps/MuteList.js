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

export default function MuteList({ pubkey }) {
  const [muteList, setMuteList] = useState({ pubkeys: [], eventIds: [], hashtags: [], words: [] })
  const [mutedProfiles, setMutedProfiles] = useState({})
  const [loading, setLoading] = useState(true)
  const [removing, setRemoving] = useState(null)

  useEffect(() => {
    if (pubkey) {
      loadMuteList()
    }
  }, [pubkey])

  const loadMuteList = async () => {
    setLoading(true)
    try {
      const list = await fetchMuteList(pubkey)
      setMuteList(list)

      if (list.pubkeys.length > 0) {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: list.pubkeys, limit: list.pubkeys.length },
          [getDefaultRelay()]
        )
        const profiles = {}
        for (const event of profileEvents) {
          profiles[event.pubkey] = parseProfile(event)
        }
        setMutedProfiles(profiles)
      }
    } catch (e) {
      console.error(e)
    } finally {
      setLoading(false)
    }
  }

  const handleUnmute = async (type, value) => {
    if (!pubkey || removing) return
    setRemoving(value)

    try {
      await removeFromMuteList(pubkey, type, value)
      if (type === 'pubkey') {
        setMuteList(prev => ({ ...prev, pubkeys: prev.pubkeys.filter(p => p !== value) }))
      } else if (type === 'hashtag') {
        setMuteList(prev => ({ ...prev, hashtags: prev.hashtags.filter(h => h !== value) }))
      } else if (type === 'word') {
        setMuteList(prev => ({ ...prev, words: prev.words.filter(w => w !== value) }))
      }
    } catch (e) {
      console.error(e)
    } finally {
      setRemoving(null)
    }
  }

  return (
    <div className="space-y-4">
      <div className="p-4 bg-[var(--bg-secondary)] rounded-2xl">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">ミュートリスト</h3>
        {loading ? (
          <div className="py-8 text-center text-[var(--text-tertiary)]">読み込み中...</div>
        ) : (
          <div className="space-y-6">
            {muteList.pubkeys.length > 0 && (
              <div>
                <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ユーザー</h4>
                <div className="space-y-2">
                  {muteList.pubkeys.map(pk => {
                    const profile = mutedProfiles[pk]
                    return (
                      <div key={pk} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                        <div className="flex items-center gap-2 min-w-0">
                          <div className="w-8 h-8 rounded-full overflow-hidden bg-[var(--bg-primary)] flex-shrink-0">
                            {profile?.picture ? (
                              <img src={profile.picture} alt="" className="w-full h-full object-cover" />
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
                        <button onClick={() => handleUnmute('pubkey', pk)} disabled={removing === pk} className="text-xs text-red-400 px-2">
                          {removing === pk ? '...' : '解除'}
                        </button>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}

            {muteList.hashtags.length > 0 && (
              <div>
                <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ハッシュタグ</h4>
                <div className="flex flex-wrap gap-2">
                  {muteList.hashtags.map(tag => (
                    <div key={tag} className="flex items-center gap-1 px-3 py-1.5 bg-[var(--bg-tertiary)] rounded-full">
                      <span className="text-sm text-[var(--text-primary)]">#{tag}</span>
                      <button onClick={() => handleUnmute('hashtag', tag)} disabled={removing === tag} className="text-red-400 ml-1">
                        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                        </svg>
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {muteList.words.length > 0 && (
              <div>
                <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-2">キーワード</h4>
                <div className="flex flex-wrap gap-2">
                  {muteList.words.map(word => (
                    <div key={word} className="flex items-center gap-1 px-3 py-1.5 bg-[var(--bg-tertiary)] rounded-full">
                      <span className="text-sm text-[var(--text-primary)]">{word}</span>
                      <button onClick={() => handleUnmute('word', word)} disabled={removing === word} className="text-red-400 ml-1">
                        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                        </svg>
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {muteList.pubkeys.length === 0 && muteList.hashtags.length === 0 && muteList.words.length === 0 && (
              <div className="py-6 text-center text-[var(--text-tertiary)]">
                <p className="text-sm">ミュート項目はありません</p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
