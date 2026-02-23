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
  loadUserGeohash,
  loadUserLocation,
  formatDistance,
  REGION_COORDINATES,
  selectRelaysByRegion,
  loadSelectedRegion,
  findNearestRelays,
  generateRelayListByLocation
} from '@/lib/geohash'

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

export default function RelaySettings({ pubkey }) {
  const [currentRelay, setCurrentRelay] = useState('wss://yabu.me')
  const [userGeohash, setUserGeohash] = useState(null)
  const [userLocation, setUserLocation] = useState(null)
  const [recommendedRelays, setRecommendedRelays] = useState([])
  const [nearestRelays, setNearestRelays] = useState([])
  const [selectedRegion, setSelectedRegion] = useState(null)
  const [detectingLocation, setDetectingLocation] = useState(false)
  const [publishingNip65, setPublishingNip65] = useState(false)
  const [nip65Config, setNip65Config] = useState(null)
  const [showNip65Details, setShowNip65Details] = useState(false)

  useEffect(() => {
    setCurrentRelay(getDefaultRelay())

    const savedGeohash = loadUserGeohash()
    if (savedGeohash) setUserGeohash(savedGeohash)

    const savedRegionId = loadSelectedRegion()
    if (savedRegionId) {
      const result = selectRelaysByRegion(savedRegionId)
      if (result.region) {
        setSelectedRegion(result.region)
        setUserGeohash(result.geohash)
        setUserLocation(result.location)
        setNip65Config(result.nip65Config)
        setNearestRelays(result.nearestRelays || [])
      }
    } else {
      const savedLocation = loadUserLocation()
      if (savedLocation) {
        try {
          const config = generateRelayListByLocation(savedLocation.lat, savedLocation.lon)
          const nearest = findNearestRelays(savedLocation.lat, savedLocation.lon, 10)
          setNip65Config(config)
          setNearestRelays(nearest)
        } catch (e) {
          console.error('Failed to restore GPS relay config:', e)
        }
      }
    }
  }, [])

  // To simplify, I'll re-import missing functions in the next step or update this file.
  // Actually I should check what else I need from geohash.
  // findNearestRelays, generateRelayListByLocation are needed.

  const handleChangeRelay = (relayUrl) => {
    setCurrentRelay(relayUrl)
    setDefaultRelay(relayUrl)
  }

  const handleAutoDetectLocation = async () => {
    setDetectingLocation(true)
    try {
      const result = await autoDetectRelays()
      if (result.geohash) {
        setUserGeohash(result.geohash)
        setUserLocation(result.location)
        setRecommendedRelays(result.relays)
        setNip65Config(result.nip65Config)
        setNearestRelays(result.nearestRelays || [])
        setSelectedRegion(null)

        if (result.nip65Config?.outbox?.length > 0) {
          handleChangeRelay(result.nip65Config.outbox[0].url)
        } else if (result.relays.length > 0) {
          handleChangeRelay(result.relays[0].url)
        }
      } else if (result.error) {
        alert(`位置情報の取得に失敗しました: ${result.error}`)
      }
    } catch (e) {
      console.error(e)
      alert('位置情報の取得に失敗しました。')
    } finally {
      setDetectingLocation(false)
    }
  }

  const handleSelectRegion = (regionId) => {
    const result = selectRelaysByRegion(regionId)
    if (result.region) {
      setSelectedRegion(result.region)
      setUserGeohash(result.geohash)
      setUserLocation(result.location)
      setRecommendedRelays(result.relays)
      setNip65Config(result.nip65Config)
      setNearestRelays(result.nearestRelays || [])

      if (result.nip65Config?.outbox?.length > 0) {
        handleChangeRelay(result.nip65Config.outbox[0].url)
      }
    }
  }

  const handleSelectNearestRelay = (relay) => {
    handleChangeRelay(relay.url)
    if (nip65Config) {
      const newOutbox = [relay, ...nip65Config.outbox.filter(r => r.url !== relay.url)].slice(0, 5)
      const newCombined = [
        { url: relay.url, read: true, write: true },
        ...nip65Config.combined.filter(r => r.url !== relay.url)
      ]
      setNip65Config({ ...nip65Config, outbox: newOutbox, combined: newCombined })
    }
  }

  const handlePublishRelayList = async () => {
    if (!pubkey || !canSign()) {
      alert('リレーリストを発行するにはログインが必要です')
      return
    }
    setPublishingNip65(true)
    try {
      let relayList = nip65Config?.combined?.length > 0
        ? nip65Config.combined
        : [{ url: currentRelay, read: true, write: true }]
      const result = await publishRelayListMetadata(relayList)
      if (result.success) {
        alert('リレーリストを発行しました (NIP-65)')
      }
    } catch (e) {
      console.error(e)
      alert('リレーリストの発行に失敗しました')
    } finally {
      setPublishingNip65(false)
    }
  }

  return (
    <div className="space-y-4">
      <div className="p-4 bg-[var(--bg-secondary)] rounded-2xl">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">リレー設定</h3>

        <div className="p-3 bg-[var(--line-green)] bg-opacity-10 rounded-xl border border-[var(--line-green)] mb-4">
          <p className="text-xs text-[var(--text-tertiary)] mb-1">現在の設定</p>
          <p className="text-sm font-medium text-[var(--text-primary)]">
            {selectedRegion ? selectedRegion.name : (userGeohash ? 'GPS検出' : '未設定')}
          </p>
          <p className="text-xs text-[var(--text-tertiary)] mt-1">
            メインリレー: {currentRelay.replace('wss://', '')}
          </p>
        </div>

        <div className="space-y-4">
          <div>
            <p className="text-sm font-medium text-[var(--text-secondary)] mb-2">地域を選択</p>
            <select
              value={selectedRegion?.id || ''}
              onChange={(e) => e.target.value && handleSelectRegion(e.target.value)}
              className="w-full py-2.5 px-3 bg-[var(--bg-primary)] text-[var(--text-primary)] rounded-lg text-sm border border-[var(--border-color)] focus:border-[var(--line-green)] focus:outline-none"
            >
              <option value="">地域を選択...</option>
              <optgroup label="日本">
                {REGION_COORDINATES.filter(r => r.country === 'JP').map(r => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
              <optgroup label="アジア">
                {REGION_COORDINATES.filter(r => ['SG', 'TW', 'KR', 'CN', 'IN'].includes(r.country)).map(r => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
              <optgroup label="北米">
                {REGION_COORDINATES.filter(r => ['US', 'CA'].includes(r.country)).map(r => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
              <optgroup label="ヨーロッパ">
                {REGION_COORDINATES.filter(r => ['EU', 'UK'].includes(r.country)).map(r => (
                  <option key={r.id} value={r.id}>{r.name}</option>
                ))}
              </optgroup>
            </select>
          </div>

          <button
            onClick={handleAutoDetectLocation}
            disabled={detectingLocation}
            className="w-full py-2 bg-[var(--bg-primary)] hover:bg-[var(--border-color)] text-[var(--text-secondary)] rounded-lg text-xs font-medium transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
          >
            {detectingLocation ? '位置情報を取得中...' : 'GPSで自動検出'}
          </button>

          {nearestRelays.length > 0 && (
            <div className="pt-3 border-t border-[var(--border-color)]">
              <p className="text-xs text-[var(--text-tertiary)] mb-2">最寄りのリレー:</p>
              <div className="space-y-1">
                {nearestRelays.slice(0, 5).map(relay => (
                  <button
                    key={relay.url}
                    onClick={() => handleSelectNearestRelay(relay)}
                    className={`w-full text-left px-3 py-2 rounded-lg text-xs transition-colors flex items-center justify-between ${
                      currentRelay === relay.url
                        ? 'bg-[var(--line-green)] text-white'
                        : 'bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--border-color)]'
                    }`}
                  >
                    <span>{relay.name} ({relay.region})</span>
                    <span className="opacity-70">{formatDistance(relay.distance)}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {pubkey && canSign() && (
            <button
              onClick={handlePublishRelayList}
              disabled={publishingNip65}
              className="w-full py-2.5 bg-purple-500 hover:bg-purple-600 text-white rounded-lg text-sm font-medium transition-colors disabled:opacity-50"
            >
              {publishingNip65 ? '発行中...' : 'リレーリストを発行 (NIP-65)'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
