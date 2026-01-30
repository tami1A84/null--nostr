'use client'

import { useState, useEffect, useCallback } from 'react'
import type { LoginMethod } from '../types'

interface UseSettingsOptions {
  pubkey: string | null
}

interface MuteList {
  pubkeys: string[]
  eventIds: string[]
  hashtags: string[]
  words: string[]
}

interface UseSettingsReturn {
  // Zap settings
  defaultZapAmount: number
  setDefaultZapAmount: (amount: number) => void

  // Relay settings
  currentRelay: string
  setCurrentRelay: (relay: string) => void

  // Upload settings
  uploadServer: string
  setUploadServer: (server: string) => void

  // Mute list
  muteList: MuteList
  mutedProfiles: Record<string, any>
  muteListLoading: boolean
  loadMuteList: () => Promise<void>
  handleUnmute: (type: 'pubkey' | 'hashtag' | 'word', value: string) => Promise<void>

  // Login
  loginMethod: LoginMethod | null

  // State
  loading: boolean
}

/**
 * Custom hook for settings management
 * Handles zap amounts, relay settings, upload server, and mute list
 */
export function useSettings({ pubkey }: UseSettingsOptions): UseSettingsReturn {
  const [defaultZapAmount, setDefaultZapState] = useState(21)
  const [currentRelay, setCurrentRelayState] = useState('wss://yabu.me')
  const [uploadServer, setUploadServerState] = useState('nostr.build')
  const [muteList, setMuteList] = useState<MuteList>({
    pubkeys: [],
    eventIds: [],
    hashtags: [],
    words: []
  })
  const [mutedProfiles, setMutedProfiles] = useState<Record<string, any>>({})
  const [muteListLoading, setMuteListLoading] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loginMethod, setLoginMethod] = useState<LoginMethod | null>(null)

  // Load settings from localStorage on mount
  useEffect(() => {
    if (typeof window === 'undefined') return

    // Load zap amount
    const savedZap = localStorage.getItem('defaultZapAmount')
    if (savedZap) {
      setDefaultZapState(parseInt(savedZap, 10))
    }

    // Load relay
    const savedRelay = localStorage.getItem('nurunuru_default_relay')
    if (savedRelay) {
      setCurrentRelayState(savedRelay)
    }

    // Load upload server
    const savedUpload = localStorage.getItem('nurunuru_upload_server')
    if (savedUpload) {
      setUploadServerState(savedUpload)
    }

    // Load login method
    const method = localStorage.getItem('nurunuru_login_method') as LoginMethod | null
    setLoginMethod(method)

    setLoading(false)
  }, [])

  // Load mute list when pubkey changes
  useEffect(() => {
    if (pubkey) {
      loadMuteList()
    }
  }, [pubkey])

  // Set default zap amount
  const setDefaultZapAmount = useCallback((amount: number) => {
    setDefaultZapState(amount)
    if (typeof window !== 'undefined') {
      localStorage.setItem('defaultZapAmount', amount.toString())
    }
  }, [])

  // Set current relay
  const setCurrentRelay = useCallback((relay: string) => {
    setCurrentRelayState(relay)
    if (typeof window !== 'undefined') {
      localStorage.setItem('nurunuru_default_relay', relay)
    }
  }, [])

  // Set upload server
  const setUploadServer = useCallback((server: string) => {
    setUploadServerState(server)
    if (typeof window !== 'undefined') {
      localStorage.setItem('nurunuru_upload_server', server)
    }
  }, [])

  // Load mute list
  const loadMuteList = useCallback(async () => {
    if (!pubkey) return

    setMuteListLoading(true)
    try {
      const { fetchMuteList, fetchEvents, parseProfile, getDefaultRelay } = await import('@/lib/nostr')
      const list = await fetchMuteList(pubkey)
      setMuteList(list)

      // Fetch profiles for muted pubkeys
      if (list.pubkeys.length > 0) {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: list.pubkeys, limit: list.pubkeys.length },
          [getDefaultRelay()]
        )
        const profiles: Record<string, any> = {}
        for (const event of profileEvents as any[]) {
          profiles[event.pubkey] = parseProfile(event)
        }
        setMutedProfiles(profiles)
      }
    } catch (e) {
      console.error('Failed to load mute list:', e)
    } finally {
      setMuteListLoading(false)
    }
  }, [pubkey])

  // Handle unmute
  const handleUnmute = useCallback(async (type: 'pubkey' | 'hashtag' | 'word', value: string) => {
    if (!pubkey) return

    try {
      const { removeFromMuteList } = await import('@/lib/nostr')
      await removeFromMuteList(pubkey, type, value)

      // Update local state
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
      throw e
    }
  }, [pubkey])

  return {
    // Zap settings
    defaultZapAmount,
    setDefaultZapAmount,

    // Relay settings
    currentRelay,
    setCurrentRelay,

    // Upload settings
    uploadServer,
    setUploadServer,

    // Mute list
    muteList,
    mutedProfiles,
    muteListLoading,
    loadMuteList,
    handleUnmute,

    // Login
    loginMethod,

    // State
    loading
  }
}

export default useSettings
