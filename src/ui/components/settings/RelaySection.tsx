'use client'

import { useState, useEffect } from 'react'
import {
  getDefaultRelay,
  setDefaultRelay,
  canSign,
  publishRelayListMetadata
} from '@/lib/nostr'
import {
  autoDetectRelays,
  formatDistance,
  REGION_COORDINATES,
  selectRelaysByRegion,
  loadSelectedRegion
} from '@/lib/geohash'

// Popular relay list
const KNOWN_RELAYS = [
  { url: 'wss://yabu.me', name: 'やぶみ (デフォルト)', region: 'JP' },
  { url: 'wss://relay-jp.nostr.wirednet.jp', name: 'WiredNet JP', region: 'JP' },
  { url: 'wss://r.kojira.io', name: 'Kojira', region: 'JP' },
  { url: 'wss://nos.lol', name: 'nos.lol', region: 'Global' },
  { url: 'wss://relay.damus.io', name: 'Damus', region: 'Global' },
  { url: 'wss://relay.snort.social', name: 'Snort', region: 'Global' },
  { url: 'wss://nostr.wine', name: 'nostr.wine (有料)', region: 'Global' },
  { url: 'wss://relay.nostr.bg', name: 'nostr.bg', region: 'EU' },
]

interface RelaySectionProps {
  pubkey: string | null
  expanded?: boolean
  onToggle?: () => void
}

interface NearestRelay {
  url: string
  name: string
  region: string
  distance: number
}

interface Nip65Config {
  outbox?: NearestRelay[]
  inbox?: NearestRelay[]
  discover?: { url: string; name: string }[]
  combined?: { url: string; read: boolean; write: boolean }[]
}

interface Region {
  id: string
  name: string
  country: string
}

export default function RelaySection({ pubkey, expanded = false, onToggle }: RelaySectionProps) {
  const [showSettings, setShowSettings] = useState(expanded)
  const [currentRelay, setCurrentRelay] = useState('wss://yabu.me')
  const [detectingLocation, setDetectingLocation] = useState(false)
  const [userGeohash, setUserGeohash] = useState<string | null>(null)
  const [userLocation, setUserLocation] = useState<any>(null)
  const [selectedRegion, setSelectedRegion] = useState<Region | null>(null)
  const [nearestRelays, setNearestRelays] = useState<NearestRelay[]>([])
  const [nip65Config, setNip65Config] = useState<Nip65Config | null>(null)
  const [showNip65Details, setShowNip65Details] = useState(false)
  const [publishingNip65, setPublishingNip65] = useState(false)

  useEffect(() => {
    setCurrentRelay(getDefaultRelay())

    const savedRegionId = loadSelectedRegion()
    if (savedRegionId) {
      const result = selectRelaysByRegion(savedRegionId) as any
      if (result.region) {
        setSelectedRegion(result.region as Region)
        setUserGeohash(result.geohash as string)
        setUserLocation(result.location)
        setNip65Config(result.nip65Config as Nip65Config)
        setNearestRelays((result.nearestRelays || []) as NearestRelay[])
      }
    }
  }, [])

  const handleChangeRelay = (relayUrl: string) => {
    setCurrentRelay(relayUrl)
    setDefaultRelay(relayUrl)
  }

  const handleSelectNearestRelay = (relay: NearestRelay) => {
    handleChangeRelay(relay.url)

    if (nip65Config) {
      const newOutbox = [relay, ...nip65Config.outbox!.filter(r => r.url !== relay.url)].slice(0, 5)
      const newCombined = [
        { url: relay.url, read: true, write: true },
        ...nip65Config.combined!.filter(r => r.url !== relay.url)
      ]
      setNip65Config({
        ...nip65Config,
        outbox: newOutbox,
        combined: newCombined
      })
    }
  }

  const handleAutoDetectLocation = async () => {
    setDetectingLocation(true)
    try {
      const result = await autoDetectRelays() as any
      if (result.geohash) {
        setUserGeohash(result.geohash as string)
        setUserLocation(result.location)
        setNip65Config(result.nip65Config as Nip65Config)
        setNearestRelays((result.nearestRelays || []) as NearestRelay[])
        setSelectedRegion(null)

        if (result.nip65Config?.outbox?.length > 0) {
          handleChangeRelay(result.nip65Config.outbox[0].url)
        } else if (result.relays?.length > 0) {
          handleChangeRelay(result.relays[0].url)
        }
      } else if (result.error) {
        alert(`位置情報の取得に失敗しました: ${result.error}`)
      }
    } catch (e) {
      console.error('Location detection failed:', e)
      alert('位置情報の取得に失敗しました。ブラウザの位置情報設定を確認してください。')
    } finally {
      setDetectingLocation(false)
    }
  }

  const handleSelectRegion = (regionId: string) => {
    const result = selectRelaysByRegion(regionId) as any
    if (result.region) {
      setSelectedRegion(result.region as Region)
      setUserGeohash(result.geohash as string)
      setUserLocation(result.location)
      setNip65Config(result.nip65Config as Nip65Config)
      setNearestRelays((result.nearestRelays || []) as NearestRelay[])

      if (result.nip65Config?.outbox?.length > 0) {
        handleChangeRelay(result.nip65Config.outbox[0].url)
      }
    }
  }

  const handlePublishRelayList = async () => {
    if (!pubkey || !canSign()) {
      alert('リレーリストを発行するにはログインが必要です')
      return
    }
    setPublishingNip65(true)
    try {
      let relayList
      if (nip65Config?.combined?.length && nip65Config.combined.length > 0) {
        relayList = nip65Config.combined
      } else {
        relayList = [{ url: currentRelay, read: true, write: true }]
      }

      const result = await publishRelayListMetadata(relayList) as { success: boolean }
      if (result.success) {
        const outboxCount = relayList.filter(r => r.write).length
        const inboxCount = relayList.filter(r => r.read).length
        alert(`リレーリストを発行しました (NIP-65)\nOutbox: ${outboxCount}リレー\nInbox: ${inboxCount}リレー`)
      }
    } catch (e: any) {
      console.error('Failed to publish relay list:', e)
      alert('リレーリストの発行に失敗しました: ' + e.message)
    } finally {
      setPublishingNip65(false)
    }
  }

  const handleToggle = () => {
    setShowSettings(!showSettings)
    onToggle?.()
  }

  return (
    <section id="relay-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
      <button
        onClick={handleToggle}
        className="w-full flex items-center justify-between"
      >
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <circle cx="12" cy="12" r="3"/>
            <path d="M12 2v4m0 12v4M2 12h4m12 0h4"/>
            <circle cx="12" cy="12" r="8" strokeDasharray="4 2"/>
          </svg>
          <h2 className="font-semibold text-[var(--text-primary)]">リレー</h2>
          <span className="text-xs text-[var(--text-tertiary)] truncate max-w-[120px]">{currentRelay.replace('wss://', '')}</span>
        </div>
        <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </button>

      {showSettings && (
        <div className="mt-4 space-y-4">
          {/* Region Display */}
          <div className="p-3 bg-[var(--line-green)] bg-opacity-10 rounded-xl border border-[var(--line-green)]">
            <p className="text-xs text-[var(--text-tertiary)] mb-1">地域</p>
            <p className="text-sm font-medium text-[var(--text-primary)]">
              {selectedRegion ? selectedRegion.name : (userGeohash ? 'GPS検出' : '未設定')}
            </p>
            <p className="text-xs text-[var(--text-tertiary)] mt-1">
              メインリレー: {currentRelay.replace('wss://', '')}
            </p>
          </div>

          {/* Region Selection */}
          <div className="p-3 bg-[var(--bg-tertiary)] rounded-xl">
            <p className="text-sm font-medium text-[var(--text-secondary)] mb-2">地域を選択</p>
            <p className="text-xs text-[var(--text-tertiary)] mb-3">
              地域を選択すると最適なリレーが自動設定されます
            </p>
            <select
              value={selectedRegion?.id || ''}
              onChange={(e) => e.target.value && handleSelectRegion(e.target.value)}
              className="w-full py-2.5 px-3 bg-[var(--bg-secondary)] text-[var(--text-primary)] rounded-lg text-sm border border-[var(--border-color)] focus:border-[var(--line-green)] focus:outline-none"
            >
              <option value="">地域を選択...</option>
              <optgroup label="日本">
                {REGION_COORDINATES.filter((r: any) => r.country === 'JP').map((r: any) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
              <optgroup label="アジア">
                {REGION_COORDINATES.filter((r: any) => ['SG', 'TW', 'KR', 'CN', 'IN'].includes(r.country)).map((r: any) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
              <optgroup label="北米">
                {REGION_COORDINATES.filter((r: any) => ['US', 'CA'].includes(r.country)).map((r: any) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
              <optgroup label="ヨーロッパ">
                {REGION_COORDINATES.filter((r: any) => ['EU', 'UK'].includes(r.country)).map((r: any) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
              <optgroup label="その他">
                {REGION_COORDINATES.filter((r: any) => ['AU', 'BR', 'Global'].includes(r.country)).map((r: any) => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
            </select>

            {/* GPS auto-detect button */}
            <div className="mt-3 pt-3 border-t border-[var(--border-color)]">
              <button
                onClick={handleAutoDetectLocation}
                disabled={detectingLocation}
                className="w-full py-2 bg-[var(--bg-secondary)] hover:bg-[var(--border-color)] text-[var(--text-secondary)] rounded-lg text-xs font-medium transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
              >
                {detectingLocation ? (
                  <>
                    <svg className="w-3.5 h-3.5 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                      <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                    </svg>
                    位置情報を取得中...
                  </>
                ) : (
                  <>
                    <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="10" r="3"/>
                      <path d="M12 21.7C17.3 17 20 13 20 10a8 8 0 1 0-16 0c0 3 2.7 7 8 11.7z"/>
                    </svg>
                    GPSで自動検出
                  </>
                )}
              </button>
            </div>

            {/* Show nearest relays with distance */}
            {nearestRelays.length > 0 && (
              <div className="mt-3 pt-3 border-t border-[var(--border-color)]">
                <p className="text-xs text-[var(--text-tertiary)] mb-2">最寄りのリレー:</p>
                <div className="space-y-1">
                  {nearestRelays.slice(0, 5).map(relay => (
                    <button
                      key={relay.url}
                      onClick={() => handleSelectNearestRelay(relay)}
                      className={`w-full text-left px-3 py-2 rounded-lg text-xs transition-colors flex items-center justify-between ${
                        currentRelay === relay.url
                          ? 'bg-[var(--line-green)] text-white'
                          : 'bg-[var(--bg-secondary)] text-[var(--text-primary)] hover:bg-[var(--border-color)]'
                      }`}
                    >
                      <span>{relay.name} ({relay.region})</span>
                      <span className="opacity-70">{formatDistance(relay.distance)}</span>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* NIP-65 Outbox Model Configuration */}
            {nip65Config && (
              <div className="mt-3 pt-3 border-t border-[var(--border-color)]">
                <button
                  onClick={() => setShowNip65Details(!showNip65Details)}
                  className="w-full flex items-center justify-between text-xs text-[var(--text-secondary)] mb-2"
                >
                  <span className="font-medium">NIP-65 Outbox Model 設定</span>
                  <svg className={`w-4 h-4 transition-transform ${showNip65Details ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polyline points="6,9 12,15 18,9"/>
                  </svg>
                </button>

                {showNip65Details && (
                  <div className="space-y-3">
                    {nip65Config.outbox && nip65Config.outbox.length > 0 && (
                      <div>
                        <p className="text-xs text-[var(--text-tertiary)] mb-1 flex items-center gap-1">
                          <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <path d="M12 19l7-7 3 3-7 7-3-3z"/>
                            <path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z"/>
                          </svg>
                          Outbox (投稿先) - {nip65Config.outbox.length}リレー
                        </p>
                        <div className="space-y-1">
                          {nip65Config.outbox.map(relay => (
                            <div key={relay.url} className="px-2 py-1 bg-green-500 bg-opacity-10 rounded text-xs text-[var(--text-primary)] flex justify-between">
                              <span className="truncate">{relay.name}</span>
                              <span className="text-green-500 opacity-70">{formatDistance(relay.distance)}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    {nip65Config.inbox && nip65Config.inbox.length > 0 && (
                      <div>
                        <p className="text-xs text-[var(--text-tertiary)] mb-1 flex items-center gap-1">
                          <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <path d="M22 12h-4l-3 9L9 3l-3 9H2"/>
                          </svg>
                          Inbox (受信先) - {nip65Config.inbox.length}リレー
                        </p>
                        <div className="space-y-1">
                          {nip65Config.inbox.map(relay => (
                            <div key={relay.url} className="px-2 py-1 bg-blue-500 bg-opacity-10 rounded text-xs text-[var(--text-primary)] flex justify-between">
                              <span className="truncate">{relay.name}</span>
                              <span className="text-blue-500 opacity-70">{formatDistance(relay.distance)}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    {nip65Config.discover && nip65Config.discover.length > 0 && (
                      <div>
                        <p className="text-xs text-[var(--text-tertiary)] mb-1 flex items-center gap-1">
                          <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <circle cx="11" cy="11" r="8"/>
                            <path d="M21 21l-4.35-4.35"/>
                          </svg>
                          Discover (NIP-65検索) - {nip65Config.discover.length}リレー
                        </p>
                        <div className="space-y-1">
                          {nip65Config.discover.map(relay => (
                            <div key={relay.url} className="px-2 py-1 bg-purple-500 bg-opacity-10 rounded text-xs text-[var(--text-primary)]">
                              <span className="truncate">{relay.name}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Publish to NIP-65 */}
          {pubkey && canSign() && (
            <div className="p-3 bg-[var(--bg-tertiary)] rounded-xl">
              <p className="text-sm font-medium text-[var(--text-secondary)] mb-2">リレーリストを発行 (NIP-65)</p>
              <p className="text-xs text-[var(--text-tertiary)] mb-3">
                現在のリレー設定を他のクライアントと共有できます
              </p>
              <button
                onClick={handlePublishRelayList}
                disabled={publishingNip65}
                className="w-full py-2.5 bg-purple-500 hover:bg-purple-600 text-white rounded-lg text-sm font-medium transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
              >
                {publishingNip65 ? (
                  <>
                    <svg className="w-4 h-4 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                      <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                    </svg>
                    発行中...
                  </>
                ) : (
                  <>
                    <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M12 19l7-7 3 3-7 7-3-3z"/>
                      <path d="M18 13l-1.5-7.5L2 2l3.5 14.5L13 18l5-5z"/>
                      <path d="M2 2l7.586 7.586"/>
                      <circle cx="11" cy="11" r="2"/>
                    </svg>
                    リレーリストを発行
                  </>
                )}
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
