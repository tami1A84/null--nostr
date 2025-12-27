'use client'

import { useState, useEffect, useCallback } from 'react'
import { nip19 } from 'nostr-tools'
import {
  parseProfile,
  signEventNip07,
  publishEvent,
  getDefaultRelay,
  shortenPubkey
} from '@/lib/nostr'

// Chronostr event kinds
const KIND_CHRONOSTR_EVENT = 31928  // Parent schedule event
const KIND_DATE_CANDIDATE = 31926   // Date candidate (child event)
const KIND_CALENDAR_RSVP = 31925

// Fast relays for calendar events (Japanese relays only)
const CALENDAR_RELAYS = [
  'wss://yabu.me',
  'wss://relay-jp.nostr.wirednet.jp'
]

// Fast fetch with short timeout
async function fastFetch(filter, relays, timeoutMs = 4000) {
  const results = []
  const seen = new Set()
  
  const fetchFromRelay = (relayUrl) => {
    return new Promise((resolve) => {
      try {
        const ws = new WebSocket(relayUrl)
        const subId = Math.random().toString(36).slice(2)
        let resolved = false
        
        const timeout = setTimeout(() => {
          if (!resolved) {
            resolved = true
            try { ws.close() } catch(e) {}
            resolve([])
          }
        }, timeoutMs)
        
        ws.onopen = () => {
          ws.send(JSON.stringify(['REQ', subId, filter]))
        }
        
        ws.onmessage = (msg) => {
          try {
            const data = JSON.parse(msg.data)
            if (data[0] === 'EVENT' && data[2]) {
              const event = data[2]
              if (!seen.has(event.id)) {
                seen.add(event.id)
                results.push(event)
              }
            } else if (data[0] === 'EOSE') {
              clearTimeout(timeout)
              if (!resolved) {
                resolved = true
                try { ws.close() } catch(e) {}
                resolve(results)
              }
            }
          } catch (e) {}
        }
        
        ws.onerror = () => {
          clearTimeout(timeout)
          if (!resolved) {
            resolved = true
            resolve([])
          }
        }
        
        ws.onclose = () => {
          clearTimeout(timeout)
          if (!resolved) {
            resolved = true
            resolve([])
          }
        }
      } catch (e) {
        resolve([])
      }
    })
  }
  
  // Fetch from all relays in parallel
  await Promise.all(relays.map(r => fetchFromRelay(r)))
  
  return results
}

// Format date for display
const formatDateShort = (dateStr) => {
  const date = new Date(dateStr)
  return date.toLocaleDateString('ja-JP', { 
    month: '2-digit', 
    day: '2-digit',
    weekday: 'short'
  })
}

// Parse chronostr URL format: https://chronostr.pages.dev/#/events/31928:pubkey:d-tag
function parseChronostrUrl(url) {
  const match = url.match(/events\/(\d+):([a-f0-9]+):(.+?)(?:\?|$)/)
  if (match) {
    return {
      kind: parseInt(match[1]),
      pubkey: match[2],
      identifier: match[3]
    }
  }
  return null
}

// Generate unique ID
const generateId = () => Math.random().toString(36).substring(2, 15) + Date.now().toString(36)

// Create Event Form Component
function CreateEventForm({ pubkey, onCreated, onCancel }) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [location, setLocation] = useState('')
  const [dates, setDates] = useState([''])
  const [creating, setCreating] = useState(false)

  const addDate = () => setDates([...dates, ''])
  
  const updateDate = (index, value) => {
    const updated = [...dates]
    updated[index] = value
    setDates(updated)
  }
  
  const removeDate = (index) => {
    if (dates.length > 1) {
      setDates(dates.filter((_, i) => i !== index))
    }
  }

  const handleCreate = async () => {
    if (!title.trim() || dates.filter(d => d).length === 0) {
      alert('タイトルと少なくとも1つの候補日を入力してください')
      return
    }
    
    setCreating(true)
    try {
      const dTag = generateId()
      const validDates = dates.filter(d => d)
      
      // Create chronostr-compatible event
      const tags = [
        ['d', dTag],
        ['title', title.trim()],
        ...validDates.map(d => ['date', d]),
        ['t', 'chousei'],
        ['client', 'nullnull-chousei']
      ]
      
      if (location.trim()) {
        tags.push(['location', location.trim()])
      }
      
      const event = {
        kind: KIND_CHRONOSTR_EVENT,
        created_at: Math.floor(Date.now() / 1000),
        tags,
        content: description || '',
        pubkey
      }
      
      const signed = await signEventNip07(event)
      await publishEvent(signed)
      
      alert('イベントを作成しました！')
      onCreated?.(signed)
    } catch (e) {
      console.error('Failed to create event:', e)
      alert('イベントの作成に失敗しました: ' + e.message)
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          タイトル *
        </label>
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="例: 新年会の日程調整"
          className="w-full px-3 py-2 rounded-lg bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)]"
        />
      </div>
      
      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          説明
        </label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="イベントの詳細..."
          rows={2}
          className="w-full px-3 py-2 rounded-lg bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)] resize-none"
        />
      </div>
      
      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          場所
        </label>
        <input
          type="text"
          value={location}
          onChange={(e) => setLocation(e.target.value)}
          placeholder="例: 渋谷駅周辺"
          className="w-full px-3 py-2 rounded-lg bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)]"
        />
      </div>
      
      <div>
        <div className="flex items-center justify-between mb-2">
          <label className="text-sm font-medium text-[var(--text-secondary)]">
            候補日 *
          </label>
          <button
            type="button"
            onClick={addDate}
            className="text-sm text-[var(--line-green)]"
          >
            + 追加
          </button>
        </div>
        <div className="space-y-2">
          {dates.map((date, index) => (
            <div key={index} className="flex items-center gap-2">
              <input
                type="date"
                value={date}
                onChange={(e) => updateDate(index, e.target.value)}
                className="flex-1 px-3 py-2 rounded-lg bg-[var(--bg-primary)] border border-[var(--border-color)] text-sm text-[var(--text-primary)] appearance-none"
                style={{ minHeight: '42px' }}
              />
              {dates.length > 1 && (
                <button
                  type="button"
                  onClick={() => removeDate(index)}
                  className="p-2 text-red-500"
                >
                  <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <line x1="18" y1="6" x2="6" y2="18"/>
                    <line x1="6" y1="6" x2="18" y2="18"/>
                  </svg>
                </button>
              )}
            </div>
          ))}
        </div>
      </div>
      
      <div className="flex gap-2 pt-2">
        <button
          type="button"
          onClick={onCancel}
          className="flex-1 py-2 rounded-full border border-[var(--border-color)] text-[var(--text-secondary)]"
        >
          キャンセル
        </button>
        <button
          type="button"
          onClick={handleCreate}
          disabled={creating}
          className="flex-1 py-2 rounded-full bg-[var(--line-green)] text-white font-medium disabled:opacity-50"
        >
          {creating ? '作成中...' : '作成する'}
        </button>
      </div>
    </div>
  )
}

// Event Detail Modal - chronostr style full screen sheet
function EventDetailModal({ event, allEvents = [], rsvps, profiles, myPubkey, onClose, onRsvp, rsvpInProgress, onDelete }) {
  const titleTag = event.tags.find(t => t[0] === 'title')?.[1]
  let nameTag = event.tags.find(t => t[0] === 'name')?.[1]
  // chronostr uses name like "イベント名-candidate-dates-N", remove suffix
  if (nameTag) {
    nameTag = nameTag.replace(/-candidate-dates-\d+$/, '')
  }
  const summaryTag = event.tags.find(t => t[0] === 'summary')?.[1]
  const dTag = event.tags.find(t => t[0] === 'd')?.[1]
  
  // Try to get title from content (first line if plain text)
  let contentTitle = null
  if (event.content) {
    try {
      const parsed = JSON.parse(event.content)
      contentTitle = parsed.name || parsed.title
    } catch (e) {
      // content is plain text, use first line if short
      const firstLine = event.content.split('\n')[0]
      if (firstLine.length < 50) {
        contentTitle = firstLine
      }
    }
  }
  
  // Don't use dTag if it looks like a UUID
  const isUUID = dTag && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(dTag)
  const title = titleTag || nameTag || summaryTag || contentTitle || (!isUUID ? dTag : null) || '無題のイベント'
  const locationTag = event.tags.find(t => t[0] === 'location')
  
  // Get date candidates from event - try multiple formats
  const dateTags = event.tags.filter(t => t[0] === 'date')
  const optionTags = event.tags.filter(t => t[0] === 'option')
  const startTags = event.tags.filter(t => t[0] === 'start')
  const slotTags = event.tags.filter(t => t[0] === 'slot')
  const candidateTags = event.tags.filter(t => t[0] === 'candidate')
  
  let dates = []
  if (dateTags.length > 0) {
    dates = dateTags.map(t => t[1]).sort()
  } else if (optionTags.length > 0) {
    dates = optionTags.map(t => t[2] || t[1]).sort()
  } else if (slotTags.length > 0) {
    dates = slotTags.map(t => t[1]).sort()
  } else if (candidateTags.length > 0) {
    dates = candidateTags.map(t => t[1]).sort()
  } else if (startTags.length > 0) {
    // chronostr uses ISO 8601 format: "2026-01-31T00:00:00.000Z"
    dates = startTags.map(t => {
      const val = t[1]
      // Check if it's ISO 8601 string
      if (val && val.includes('T')) {
        return val.split('T')[0]  // Extract date part only
      }
      // Check if it's a Unix timestamp
      const ts = parseInt(val)
      if (!isNaN(ts) && ts > 1000000000) {
        const d = new Date(ts * 1000)
        return d.toISOString().split('T')[0]
      }
      return val
    }).filter(d => d && d !== '1970-01-01').sort()
  }
  
  // For child events (kind:31926), find sibling events with same parent
  // and aggregate their dates
  if (event.kind === 31926) {
    const myATag = event.tags.find(t => t[0] === 'a')?.[1]
    if (myATag) {
      // Find all sibling events (same 'a' tag value)
      const siblingEvents = allEvents.filter(e => {
        if (e.kind !== 31926) return false
        const aTag = e.tags.find(t => t[0] === 'a')?.[1]
        return aTag === myATag
      })
      
      // Aggregate dates from all siblings
      dates = []
      siblingEvents.forEach(sibling => {
        const siblingStartTags = sibling.tags.filter(t => t[0] === 'start')
        siblingStartTags.forEach(t => {
          const val = t[1]
          let dateStr
          if (val && val.includes('T')) {
            dateStr = val.split('T')[0]
          } else {
            const ts = parseInt(val)
            if (!isNaN(ts) && ts > 1000000000) {
              dateStr = new Date(ts * 1000).toISOString().split('T')[0]
            }
          }
          if (dateStr && dateStr !== '1970-01-01' && !dates.includes(dateStr)) {
            dates.push(dateStr)
          }
        })
      })
      dates.sort()
    }
  }
  
  // If this is a parent event (kind:31928) with no dates, look for child events
  if (dates.length === 0 && event.kind === 31928) {
    const parentATag = `31928:${event.pubkey}:${dTag}`
    const childEvents = allEvents.filter(e => {
      // Child events (kind:31926) have an 'a' tag pointing to the parent
      const aTag = e.tags.find(t => t[0] === 'a')
      return aTag && (aTag[1] === parentATag || aTag[1].includes(dTag))
    })
    
    // Extract dates from child events
    childEvents.forEach(child => {
      const childStartTags = child.tags.filter(t => t[0] === 'start')
      childStartTags.forEach(t => {
        const val = t[1]
        let dateStr
        if (val && val.includes('T')) {
          dateStr = val.split('T')[0]
        } else {
          const ts = parseInt(val)
          if (!isNaN(ts) && ts > 1000000000) {
            dateStr = new Date(ts * 1000).toISOString().split('T')[0]
          }
        }
        if (dateStr && dateStr !== '1970-01-01' && !dates.includes(dateStr)) {
          dates.push(dateStr)
        }
      })
    })
    dates.sort()
  }
  
  // Get all RSVPs for this event (and children if parent event)
  let eventRsvps = []
  
  // Build a map from child event d-tag/id to date
  const eventToDateMap = new Map()
  
  if (event.kind === 31928) {
    // For parent events, look for RSVPs on all child events referenced by 'a' tags
    const childATags = event.tags.filter(t => t[0] === 'a').map(t => t[1])
    
    // Extract d-tags from a-tags (format: "31926:pubkey:d-tag")
    const childDTags = childATags.map(aTag => {
      const parts = aTag.split(':')
      return parts.length >= 3 ? parts.slice(2).join(':') : null
    }).filter(Boolean)
    
    // Find child events and build date map
    childATags.forEach(aTag => {
      const parts = aTag.split(':')
      if (parts.length >= 3) {
        const childDTag = parts.slice(2).join(':')
        // Find the child event in allEvents
        const childEvent = allEvents.find(e => {
          const eDTag = e.tags.find(t => t[0] === 'd')?.[1]
          return eDTag === childDTag
        })
        if (childEvent) {
          const startTag = childEvent.tags.find(t => t[0] === 'start')?.[1]
          if (startTag) {
            const dateStr = startTag.includes('T') ? startTag.split('T')[0] : startTag
            eventToDateMap.set(childDTag, dateStr)
            eventToDateMap.set(childEvent.id, dateStr)
          }
        }
      }
    })
    
    // Find RSVPs that reference any of the child events
    eventRsvps = rsvps.filter(r => {
      const aTag = r.tags.find(t => t[0] === 'a')
      if (aTag) {
        // Check if the RSVP's a-tag matches any child
        if (childATags.includes(aTag[1])) return true
        // Also check partial match on d-tag
        const rsvpParts = aTag[1].split(':')
        if (rsvpParts.length >= 3) {
          const rsvpDTag = rsvpParts.slice(2).join(':')
          if (childDTags.includes(rsvpDTag)) return true
        }
      }
      return false
    })
  } else if (event.kind === 31926) {
    // For child events, find RSVPs for all siblings with same parent
    const myATag = event.tags.find(t => t[0] === 'a')?.[1]
    if (myATag) {
      const siblingEvents = allEvents.filter(e => {
        if (e.kind !== 31926) return false
        const aTag = e.tags.find(t => t[0] === 'a')?.[1]
        return aTag === myATag
      })
      
      // Build date map from siblings
      siblingEvents.forEach(e => {
        const eDTag = e.tags.find(t => t[0] === 'd')?.[1]
        const startTag = e.tags.find(t => t[0] === 'start')?.[1]
        if (eDTag && startTag) {
          const dateStr = startTag.includes('T') ? startTag.split('T')[0] : startTag
          eventToDateMap.set(eDTag, dateStr)
          eventToDateMap.set(e.id, dateStr)
        }
      })
      
      // Collect RSVPs for all siblings
      const siblingATags = siblingEvents.map(e => {
        const eDTag = e.tags.find(t => t[0] === 'd')?.[1]
        return `${e.kind}:${e.pubkey}:${eDTag}`
      })
      const siblingDTags = siblingEvents.map(e => e.tags.find(t => t[0] === 'd')?.[1]).filter(Boolean)
      const siblingIds = siblingEvents.map(e => e.id)
      
      eventRsvps = rsvps.filter(r => {
        const aTag = r.tags.find(t => t[0] === 'a')
        if (aTag) {
          if (siblingATags.includes(aTag[1])) return true
          const parts = aTag[1].split(':')
          if (parts.length >= 3) {
            const refDTag = parts.slice(2).join(':')
            if (siblingDTags.includes(refDTag)) return true
          }
        }
        const eTag = r.tags.find(t => t[0] === 'e')
        return eTag && siblingIds.includes(eTag[1])
      })
    }
  } else {
    // For other event types
    const aTagValue = `${event.kind}:${event.pubkey}:${dTag}`
    
    eventRsvps = rsvps.filter(r => {
      const aTag = r.tags.find(t => t[0] === 'a')
      if (aTag && aTag[1] === aTagValue) return true
      if (aTag && dTag && aTag[1].includes(dTag)) return true
      const eTag = r.tags.find(t => t[0] === 'e')
      return eTag?.[1] === event.id
    })
  }
  
  // Group RSVPs by user and date
  const rsvpsByUser = {}
  eventRsvps.forEach(r => {
    // Check multiple tag formats for date/response
    let dateTag = r.tags.find(t => t[0] === 'date')?.[1]
    const responseTag = r.tags.find(t => t[0] === 'response')?.[1]
    const optionTag = r.tags.find(t => t[0] === 'option')?.[1]
    const status = r.tags.find(t => t[0] === 'status')?.[1] || 'accepted'
    
    let targetDate = dateTag || responseTag || optionTag
    
    // If no date found in RSVP, try to find it from the referenced event
    if (!targetDate) {
      // Try to get date from the 'a' tag reference
      const rsvpATag = r.tags.find(t => t[0] === 'a')?.[1]
      if (rsvpATag) {
        const parts = rsvpATag.split(':')
        if (parts.length >= 3) {
          const refDTag = parts.slice(2).join(':')
          targetDate = eventToDateMap.get(refDTag)
        }
      }
      // Try to get date from the 'e' tag reference
      if (!targetDate) {
        const rsvpETag = r.tags.find(t => t[0] === 'e')?.[1]
        if (rsvpETag) {
          targetDate = eventToDateMap.get(rsvpETag)
        }
      }
    }
    
    if (!rsvpsByUser[r.pubkey]) {
      rsvpsByUser[r.pubkey] = {}
    }
    if (targetDate) {
      rsvpsByUser[r.pubkey][targetDate] = status
    } else {
      // If no date can be determined, apply to all dates
      dates.forEach(d => {
        rsvpsByUser[r.pubkey][d] = status
      })
    }
  })
  
  // Get unique participants
  const participants = Object.keys(rsvpsByUser)
  
  // My current RSVPs
  const myRsvps = rsvpsByUser[myPubkey] || {}
  
  // Calculate totals per date
  const totals = {}
  dates.forEach(date => {
    totals[date] = { accepted: 0, tentative: 0, declined: 0 }
    Object.values(rsvpsByUser).forEach(userRsvps => {
      const status = userRsvps[date]
      if (status) totals[date][status]++
    })
  })
  
  const isCreator = event.pubkey === myPubkey
  
  // Handle RSVP for a specific date
  const handleDateRsvp = async (date, status) => {
    if (rsvpInProgress) return
    onRsvp(event, status, date)
  }
  
  // Copy share link
  const copyLink = () => {
    const link = `https://chronostr.pages.dev/#/events/${event.kind}:${event.pubkey}:${dTag}`
    navigator.clipboard.writeText(link)
    alert('リンクをコピーしました')
  }

  // Delete event
  const handleDeleteEvent = async () => {
    if (!onDelete) return
    if (!confirm('このイベントを削除しますか？')) return
    await onDelete(event)
    onClose()
  }

  // Date range
  const dateRange = dates.length > 0
    ? `${formatDateShort(dates[0])} ~ ${formatDateShort(dates[dates.length - 1])}`
    : ''

  return (
    <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-0 sm:p-4">
      <div className="bg-[var(--bg-primary)] w-full h-[calc(100%-80px)] sm:w-[90%] sm:max-w-2xl sm:max-h-[90vh] sm:h-auto sm:rounded-2xl flex flex-col overflow-hidden">
        {/* Header - Fixed */}
        <div className="flex-shrink-0 border-b border-[var(--border-color)] p-4">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-bold text-[var(--text-primary)] flex-1 truncate pr-2">{title}</h2>
            <button onClick={onClose} className="p-2 text-[var(--text-tertiary)] flex-shrink-0 -mr-2">
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          
          {/* Creator */}
          <div className="flex items-center gap-2 mt-2">
            {profiles[event.pubkey]?.picture ? (
              <img 
                src={profiles[event.pubkey].picture} 
                alt="" 
                className="w-6 h-6 rounded-full"
                referrerPolicy="no-referrer"
              />
            ) : (
              <div className="w-6 h-6 rounded-full bg-[var(--bg-tertiary)]" />
            )}
            <span className="text-sm text-[var(--text-secondary)]">
              {profiles[event.pubkey]?.name || shortenPubkey(event.pubkey)}
            </span>
          </div>
        </div>
        
        {/* Content - Scrollable */}
      <div className="flex-1 overflow-y-auto">
        <div className="p-4 space-y-4">
          {/* Description */}
          {event.content && (
            <p className="text-sm text-[var(--text-primary)] whitespace-pre-wrap">{event.content}</p>
          )}
          
          {/* Location */}
          {locationTag && (
            <p className="text-sm text-[var(--text-tertiary)] flex items-center gap-1">
              <svg className="w-4 h-4 flex-shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0118 0z"/>
                <circle cx="12" cy="10" r="3"/>
              </svg>
              {locationTag[1]}
            </p>
          )}
          
          {/* Info row - chronostr style */}
          {dates.length > 0 && (
            <div className="flex items-center gap-4 text-sm text-[var(--text-secondary)]">
              <span className="flex items-center gap-1">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/>
                  <circle cx="9" cy="7" r="4"/>
                  <path d="M23 21v-2a4 4 0 00-3-3.87"/>
                  <path d="M16 3.13a4 4 0 010 7.75"/>
                </svg>
                {participants.length}
              </span>
              <span className="flex items-center gap-1">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
                  <line x1="16" y1="2" x2="16" y2="6"/>
                  <line x1="8" y1="2" x2="8" y2="6"/>
                  <line x1="3" y1="10" x2="21" y2="10"/>
                </svg>
                {dateRange}
              </span>
            </div>
          )}
          
          {/* Participation table - chronostr style */}
          <h3 className="text-sm font-medium text-[var(--text-primary)] pt-2 border-t border-[var(--border-color)]">
            参加者
          </h3>
          
          {dates.length > 0 && (
            <div className="border border-[var(--border-color)] rounded-lg overflow-hidden">
              <div className="overflow-x-auto overflow-y-auto max-h-[400px] -mx-4 px-4 sm:mx-0 sm:px-0">
                <table className="w-full text-sm min-w-max">
                  <thead>
                    <tr className="bg-[var(--bg-tertiary)]">
                      <th className="p-3 text-left font-medium text-[var(--text-secondary)] border-r border-[var(--border-color)] min-w-[100px]">
                        Name
                      </th>
                      {dates.map(date => (
                        <th key={date} className="p-3 text-center font-medium text-[var(--text-secondary)] border-r border-[var(--border-color)] last:border-r-0 whitespace-nowrap min-w-[80px]">
                          {formatDateShort(date)}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {/* Participant rows */}
                    {participants.map(pk => {
                      const profile = profiles[pk]
                      const userRsvps = rsvpsByUser[pk]
                      const isMe = pk === myPubkey
                      return (
                        <tr 
                          key={pk} 
                          className={`border-t border-[var(--border-color)] ${isMe ? 'bg-[var(--line-green)]/10' : ''}`}
                        >
                          <td className="p-3 border-r border-[var(--border-color)] bg-[var(--bg-primary)]">
                            <div className="flex items-center gap-2">
                              {profile?.picture ? (
                                <img src={profile.picture} alt="" className="w-5 h-5 rounded-full flex-shrink-0" referrerPolicy="no-referrer" />
                              ) : (
                                <div className="w-5 h-5 rounded-full bg-[var(--bg-tertiary)] flex-shrink-0" />
                              )}
                              <span className="truncate text-[var(--text-primary)]">
                                {profile?.name || shortenPubkey(pk)}
                              </span>
                            </div>
                          </td>
                          {dates.map(date => {
                            const status = userRsvps[date]
                            return (
                              <td key={date} className="p-3 text-center border-r border-[var(--border-color)] last:border-r-0">
                                {status === 'accepted' && <span className="text-green-500 text-lg">○</span>}
                                {status === 'tentative' && <span className="text-yellow-500 text-lg">△</span>}
                                {status === 'declined' && <span className="text-red-500 text-lg">×</span>}
                                {!status && <span className="text-[var(--text-tertiary)]">-</span>}
                              </td>
                            )
                          })}
                        </tr>
                      )
                    })}
                    
                    {/* Empty state */}
                    {participants.length === 0 && (
                      <tr className="border-t border-[var(--border-color)]">
                        <td colSpan={dates.length + 1} className="p-4 text-center text-[var(--text-tertiary)]">
                          まだ回答がありません
                        </td>
                      </tr>
                    )}
                    
                    {/* Total row */}
                    <tr className="border-t border-[var(--border-color)] bg-[var(--bg-tertiary)]">
                      <td className="p-3 font-medium text-[var(--text-secondary)] border-r border-[var(--border-color)] bg-[var(--bg-tertiary)]">
                        Total
                      </td>
                      {dates.map(date => (
                        <td key={date} className="p-3 text-center border-r border-[var(--border-color)] last:border-r-0">
                          <div className="text-green-500">○{totals[date].accepted}</div>
                          <div className="text-yellow-500">△{totals[date].tentative}</div>
                        </td>
                      ))}
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          )}
          
          {/* Copy link */}
          <button
            type="button"
            onClick={copyLink}
            className="w-full py-3 rounded-lg border border-[var(--border-color)] text-[var(--text-secondary)] text-sm flex items-center justify-center gap-2"
          >
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
              <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/>
            </svg>
            リンクをコピー
          </button>

          {/* Delete button - only show for event creator */}
          {myPubkey && event.pubkey === myPubkey && (
            <button
              type="button"
              onClick={handleDeleteEvent}
              className="w-full py-3 rounded-lg border border-red-500/50 text-red-500 text-sm flex items-center justify-center gap-2 hover:bg-red-500/10"
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/>
                <line x1="10" y1="11" x2="10" y2="17"/>
                <line x1="14" y1="11" x2="14" y2="17"/>
              </svg>
              イベントを削除
            </button>
          )}
        </div>
      </div>
      
      {/* Footer - Fixed RSVP Button - show for all logged in users */}
      {myPubkey && dates.length > 0 && (
        <div className="flex-shrink-0 border-t border-[var(--border-color)] p-4 bg-[var(--bg-primary)]">
          <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-2">回答する</h4>
          <div className="space-y-2 max-h-[200px] overflow-y-auto">
            {dates.map(date => (
              <div key={date} className="flex items-center gap-2">
                <span className="text-sm text-[var(--text-primary)] w-24 truncate">{formatDateShort(date)}</span>
                <div className="flex gap-1 flex-1 justify-end">
                  <button
                    type="button"
                    onClick={() => handleDateRsvp(date, 'accepted')}
                    disabled={rsvpInProgress}
                    className={`px-4 py-2 rounded text-sm font-medium ${
                      myRsvps[date] === 'accepted'
                        ? 'bg-green-500 text-white'
                        : 'bg-[var(--bg-tertiary)] text-green-500'
                    }`}
                  >
                    ○
                  </button>
                  <button
                    type="button"
                    onClick={() => handleDateRsvp(date, 'tentative')}
                    disabled={rsvpInProgress}
                    className={`px-4 py-2 rounded text-sm font-medium ${
                      myRsvps[date] === 'tentative'
                        ? 'bg-yellow-500 text-white'
                        : 'bg-[var(--bg-tertiary)] text-yellow-500'
                    }`}
                  >
                    △
                  </button>
                  <button
                    type="button"
                    onClick={() => handleDateRsvp(date, 'declined')}
                    disabled={rsvpInProgress}
                    className={`px-4 py-2 rounded text-sm font-medium ${
                      myRsvps[date] === 'declined'
                        ? 'bg-red-500 text-white'
                        : 'bg-[var(--bg-tertiary)] text-red-500'
                    }`}
                  >
                    ×
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
      </div>
    </div>
  )
}

// Event Card Component
function EventCard({ event, allEvents = [], rsvps, profiles, myPubkey, onViewDetails }) {
  const titleTag = event.tags.find(t => t[0] === 'title')?.[1]
  let nameTag = event.tags.find(t => t[0] === 'name')?.[1]
  // chronostr uses name like "イベント名-candidate-dates-N", remove suffix
  if (nameTag) {
    nameTag = nameTag.replace(/-candidate-dates-\d+$/, '')
  }
  const summaryTag = event.tags.find(t => t[0] === 'summary')?.[1]
  const dTag = event.tags.find(t => t[0] === 'd')?.[1]
  
  // Try to get title from content (first line if plain text)
  let contentTitle = null
  if (event.content) {
    try {
      const parsed = JSON.parse(event.content)
      contentTitle = parsed.name || parsed.title
    } catch (e) {
      // content is plain text, use first line if short
      const firstLine = event.content.split('\n')[0]
      if (firstLine.length < 50) {
        contentTitle = firstLine
      }
    }
  }
  
  // Don't use dTag if it looks like a UUID
  const isUUID = dTag && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(dTag)
  const title = titleTag || nameTag || summaryTag || contentTitle || (!isUUID ? dTag : null) || '無題のイベント'
  
  const creatorProfile = profiles[event.pubkey]
  const isCreator = event.pubkey === myPubkey
  
  // Get date candidates - try multiple formats
  let dateCount = 0
  const dateTags = event.tags.filter(t => t[0] === 'date')
  const optionTags = event.tags.filter(t => t[0] === 'option')
  const startTags = event.tags.filter(t => t[0] === 'start')
  const slotTags = event.tags.filter(t => t[0] === 'slot')
  const candidateTags = event.tags.filter(t => t[0] === 'candidate')
  
  dateCount = dateTags.length || optionTags.length || startTags.length || slotTags.length || candidateTags.length
  
  // For parent events (kind:31928), count child events referenced by 'a' tags
  if (event.kind === 31928 && dateCount === 0) {
    const childATags = event.tags.filter(t => t[0] === 'a').map(t => t[1])
    dateCount = childATags.length
  }
  
  // For child events, count all siblings with same parent
  if (event.kind === 31926 && dateCount === 1) {
    const myATag = event.tags.find(t => t[0] === 'a')?.[1]
    if (myATag) {
      const siblingEvents = allEvents.filter(e => {
        if (e.kind !== 31926) return false
        const aTag = e.tags.find(t => t[0] === 'a')?.[1]
        return aTag === myATag
      })
      dateCount = siblingEvents.length
    }
  }
  
  // Get RSVPs count
  let uniqueParticipants = 0
  
  if (event.kind === 31928) {
    // For parent events, look for RSVPs on child events referenced by 'a' tags
    const childATags = event.tags.filter(t => t[0] === 'a').map(t => t[1])
    const childDTags = childATags.map(aTag => {
      const parts = aTag.split(':')
      return parts.length >= 3 ? parts.slice(2).join(':') : null
    }).filter(Boolean)
    
    const allRsvps = rsvps.filter(r => {
      const aTag = r.tags.find(t => t[0] === 'a')
      if (aTag) {
        if (childATags.includes(aTag[1])) return true
        const rsvpParts = aTag[1].split(':')
        if (rsvpParts.length >= 3) {
          const rsvpDTag = rsvpParts.slice(2).join(':')
          if (childDTags.includes(rsvpDTag)) return true
        }
      }
      return false
    })
    uniqueParticipants = new Set(allRsvps.map(r => r.pubkey)).size
  } else if (event.kind === 31926) {
    // For child events, aggregate from siblings
    const myATag = event.tags.find(t => t[0] === 'a')?.[1]
    if (myATag) {
      const siblingEvents = allEvents.filter(e => {
        if (e.kind !== 31926) return false
        const aTag = e.tags.find(t => t[0] === 'a')?.[1]
        return aTag === myATag
      })
      const siblingDTags = siblingEvents.map(e => e.tags.find(t => t[0] === 'd')?.[1]).filter(Boolean)
      const siblingIds = siblingEvents.map(e => e.id)
      
      const allRsvps = rsvps.filter(r => {
        const aTag = r.tags.find(t => t[0] === 'a')
        if (aTag) {
          const refDTag = aTag[1].split(':').slice(2).join(':')
          if (siblingDTags.some(sd => sd === refDTag || aTag[1].includes(sd))) return true
        }
        const eTag = r.tags.find(t => t[0] === 'e')
        return eTag && siblingIds.includes(eTag[1])
      })
      uniqueParticipants = new Set(allRsvps.map(r => r.pubkey)).size
    }
  } else {
    // For other event types
    const aTagValue = `${event.kind}:${event.pubkey}:${dTag}`
    const eventRsvps = rsvps.filter(r => {
      const aTag = r.tags.find(t => t[0] === 'a')
      if (aTag && aTag[1] === aTagValue) return true
      if (dTag && aTag && aTag[1].includes(dTag)) return true
      const eTag = r.tags.find(t => t[0] === 'e')
      return eTag?.[1] === event.id
    })
    uniqueParticipants = new Set(eventRsvps.map(r => r.pubkey)).size
  }
  
  return (
    <div 
      className="p-4 bg-[var(--bg-tertiary)] rounded-xl cursor-pointer hover:bg-[var(--bg-secondary)] transition-colors"
      onClick={() => onViewDetails(event)}
    >
      <div className="flex items-center gap-2 mb-2">
        {creatorProfile?.picture ? (
          <img 
            src={creatorProfile.picture} 
            alt="" 
            className="w-6 h-6 rounded-full object-cover"
            referrerPolicy="no-referrer"
          />
        ) : (
          <div className="w-6 h-6 rounded-full bg-[var(--bg-secondary)]" />
        )}
        <span className="text-xs text-[var(--text-tertiary)]">
          {creatorProfile?.name || shortenPubkey(event.pubkey)}
          {isCreator && ' (自分)'}
        </span>
      </div>
      
      <h3 className="font-medium text-[var(--text-primary)] mb-2">{title}</h3>
      
      <div className="flex items-center gap-4 text-xs text-[var(--text-tertiary)]">
        <span className="flex items-center gap-1">
          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
            <line x1="16" y1="2" x2="16" y2="6"/>
            <line x1="8" y1="2" x2="8" y2="6"/>
            <line x1="3" y1="10" x2="21" y2="10"/>
          </svg>
          {dateCount}候補
        </span>
        <span className="flex items-center gap-1">
          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/>
            <circle cx="9" cy="7" r="4"/>
            <path d="M23 21v-2a4 4 0 00-3-3.87"/>
            <path d="M16 3.13a4 4 0 010 7.75"/>
          </svg>
          {uniqueParticipants}人
        </span>
      </div>
      
      <div className="mt-3 text-sm text-[var(--line-green)]">
        詳細を見る
      </div>
    </div>
  )
}

// Main Scheduler App Component
export default function SchedulerApp({ pubkey }) {
  const [events, setEvents] = useState([])
  const [allEventsData, setAllEventsData] = useState([])  // Full list including children
  const [rsvps, setRsvps] = useState([])
  const [profiles, setProfiles] = useState({})
  const [loading, setLoading] = useState(false)
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [selectedEvent, setSelectedEvent] = useState(null)
  const [activeTab, setActiveTab] = useState('mine')
  const [rsvpInProgress, setRsvpInProgress] = useState(false)
  
  // Search state
  const [showSearch, setShowSearch] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [searching, setSearching] = useState(false)

  // Parse search query (supports naddr and chronostr URL)
  const parseSearchQuery = (query) => {
    query = query.trim()
    
    // Remove nostr: prefix
    if (query.startsWith('nostr:')) {
      query = query.slice(6)
    }
    
    // Try naddr format
    if (query.startsWith('naddr')) {
      try {
        const decoded = nip19.decode(query)
        if (decoded.type === 'naddr') {
          return decoded.data
        }
      } catch (e) {}
    }
    
    // Try chronostr URL format
    const chronostrData = parseChronostrUrl(query)
    if (chronostrData) {
      return chronostrData
    }
    
    return null
  }

  // Search handler
  const handleSearch = async () => {
    if (!searchQuery.trim()) return
    
    const parsed = parseSearchQuery(searchQuery)
    if (!parsed) {
      alert('naddr形式またはchronostrのURLを入力してください')
      return
    }
    
    setSearching(true)
    try {
      const { kind, pubkey: eventPubkey, identifier, relays } = parsed
      // Use more relays for search to find RSVPs
      const searchRelays = relays?.length > 0 ? relays : [
        ...CALENDAR_RELAYS,
        'wss://nos.lol',
        'wss://relay.damus.io'
      ]
      
      const results = await fastFetch(
        {
          kinds: [kind],
          authors: [eventPubkey],
          '#d': [identifier]
        },
        searchRelays
      )
      
      if (results.length > 0) {
        const event = results[0]
        
        const aTagValue = `${event.kind}:${event.pubkey}:${identifier}`
        
        // Fetch RSVPs - try both #a tag and #e tag methods
        const [rsvpsByA, rsvpsByE] = await Promise.all([
          fastFetch(
            { kinds: [KIND_CALENDAR_RSVP], '#a': [aTagValue] },
            searchRelays
          ),
          fastFetch(
            { kinds: [KIND_CALENDAR_RSVP], '#e': [event.id] },
            searchRelays
          )
        ])
        
        // Merge RSVPs from both methods
        const eventRsvpsMap = new Map()
        rsvpsByA.forEach(r => eventRsvpsMap.set(r.id, r))
        rsvpsByE.forEach(r => eventRsvpsMap.set(r.id, r))
        const eventRsvps = Array.from(eventRsvpsMap.values())
        
        setRsvps(prev => {
          const existing = prev.filter(r => !eventRsvps.find(er => er.id === r.id))
          return [...existing, ...eventRsvps]
        })
        
        // Fetch profiles
        const pubkeys = new Set([event.pubkey, ...eventRsvps.map(r => r.pubkey)])
        const profileEvents = await fastFetch(
          { kinds: [0], authors: Array.from(pubkeys) },
          searchRelays
        )
        const profileMap = {}
        profileEvents.forEach(e => {
          profileMap[e.pubkey] = parseProfile(e)
        })
        setProfiles(prev => ({ ...prev, ...profileMap }))
        
        setSelectedEvent(event)
        setShowSearch(false)
        setSearchQuery('')
      } else {
        alert('イベントが見つかりませんでした')
      }
    } catch (e) {
      console.error('Search failed:', e)
      alert('検索に失敗しました')
    } finally {
      setSearching(false)
    }
  }

  // Load data
  const loadData = useCallback(async () => {
    if (!pubkey) return
    
    setLoading(true)
    try {
      // Fetch my events (both parent and child kinds)
      const [myParentEvents, myChildEvents] = await Promise.all([
        fastFetch(
          { kinds: [KIND_CHRONOSTR_EVENT], authors: [pubkey], limit: 30 },
          CALENDAR_RELAYS
        ),
        fastFetch(
          { kinds: [KIND_DATE_CANDIDATE], authors: [pubkey], limit: 50 },
          CALENDAR_RELAYS
        )
      ])
      
      // Fetch my RSVPs
      const myRsvps = await fastFetch(
        { kinds: [KIND_CALENDAR_RSVP], authors: [pubkey], limit: 50 },
        CALENDAR_RELAYS
      )

      // Get event references from RSVPs - check both 'a' and 'e' tags
      const rsvpATags = myRsvps
        .map(r => r.tags.find(t => t[0] === 'a')?.[1])
        .filter(Boolean)

      const rsvpETags = myRsvps
        .map(r => r.tags.find(t => t[0] === 'e')?.[1])
        .filter(Boolean)

      // Fetch participating events (limit to avoid timeout)
      let participatingEvents = []

      // Fetch by 'a' tags (addressable events)
      const uniqueATags = [...new Set(rsvpATags)].slice(0, 20)
      for (const ref of uniqueATags) {
        const parts = ref.split(':')
        if (parts.length >= 3) {
          const [kind, pk, ...identifierParts] = parts
          const identifier = identifierParts.join(':')
          const events = await fastFetch(
            { kinds: [parseInt(kind)], authors: [pk], '#d': [identifier], limit: 1 },
            CALENDAR_RELAYS,
            3000
          )
          participatingEvents.push(...events)
        }
      }

      // Fetch by 'e' tags (event IDs) - in batches
      const uniqueETags = [...new Set(rsvpETags)].slice(0, 20)
      if (uniqueETags.length > 0) {
        const eventsByIds = await fastFetch(
          { ids: uniqueETags },
          CALENDAR_RELAYS,
          5000
        )
        participatingEvents.push(...eventsByIds)
      }
      
      // Merge all events
      const allEventsMap = new Map()
      myParentEvents.forEach(e => allEventsMap.set(e.id, e))
      myChildEvents.forEach(e => allEventsMap.set(e.id, e))
      participatingEvents.forEach(e => allEventsMap.set(e.id, e))
      
      // Filter to only valid scheduler events
      let allEvents = Array.from(allEventsMap.values()).filter(e => {
        const hasTitle = e.tags.some(t => t[0] === 'title')
        const hasName = e.tags.some(t => t[0] === 'name')
        const hasDates = e.tags.some(t => t[0] === 'date')
        const hasOptions = e.tags.some(t => t[0] === 'option')
        const hasStart = e.tags.some(t => t[0] === 'start')
        const hasSlot = e.tags.some(t => t[0] === 'slot')
        const hasCandidate = e.tags.some(t => t[0] === 'candidate')
        return hasTitle || hasName || hasDates || hasOptions || hasStart || hasSlot || hasCandidate
      })
      
      // Save full list including children (for modal to find child events)
      setAllEventsData(allEvents)
      
      // Group events: prefer parent events (kind:31928), hide children if parent exists
      const parentDTags = new Set(
        allEvents
          .filter(e => e.kind === 31928)
          .map(e => e.tags.find(t => t[0] === 'd')?.[1])
          .filter(Boolean)
      )
      
      // Filter out child events that have a parent in the list (for display only)
      const displayEvents = allEvents.filter(e => {
        // Always show nullnull-style events (kind:31928 with date tags)
        if (e.kind === 31928) return true
        
        // For child events (kind:31926), check if parent exists
        if (e.kind === 31926) {
          const aTag = e.tags.find(t => t[0] === 'a')?.[1]
          if (aTag) {
            // Extract parent d-tag from a-tag (format: "31928:pubkey:d-tag")
            const parts = aTag.split(':')
            if (parts.length >= 3) {
              const parentDTag = parts.slice(2).join(':')
              // Hide child if parent exists in the list
              if (parentDTags.has(parentDTag)) {
                return false
              }
            }
          }
        }
        
        return true
      })
      
      setEvents(displayEvents)
      
      // Fetch all RSVPs for these events - try both #a and #e methods
      const aTags = allEvents.map(e => {
        const dTag = e.tags.find(t => t[0] === 'd')?.[1]
        return `${e.kind}:${e.pubkey}:${dTag}`
      })
      const eventIds = allEvents.map(e => e.id)
      
      if (aTags.length > 0) {
        const [rsvpsByA, rsvpsByE] = await Promise.all([
          fastFetch(
            { kinds: [KIND_CALENDAR_RSVP], '#a': aTags, limit: 200 },
            CALENDAR_RELAYS
          ),
          fastFetch(
            { kinds: [KIND_CALENDAR_RSVP], '#e': eventIds, limit: 200 },
            CALENDAR_RELAYS
          )
        ])
        
        // Merge RSVPs
        const rsvpMap = new Map()
        myRsvps.forEach(r => rsvpMap.set(r.id, r))
        rsvpsByA.forEach(r => rsvpMap.set(r.id, r))
        rsvpsByE.forEach(r => rsvpMap.set(r.id, r))
        setRsvps(Array.from(rsvpMap.values()))
        
        // Fetch profiles
        const pubkeys = new Set([
          ...allEvents.map(e => e.pubkey),
          ...Array.from(rsvpMap.values()).map(r => r.pubkey)
        ])
        const profileEvents = await fastFetch(
          { kinds: [0], authors: Array.from(pubkeys).slice(0, 30) },
          CALENDAR_RELAYS
        )
        const profileMap = {}
        profileEvents.forEach(e => {
          profileMap[e.pubkey] = parseProfile(e)
        })
        setProfiles(profileMap)
      }
    } catch (e) {
      console.error('Failed to load:', e)
    } finally {
      setLoading(false)
    }
  }, [pubkey])

  useEffect(() => {
    if (pubkey) loadData()
  }, [pubkey, loadData])

  // Handle RSVP
  const handleRsvp = async (event, status, date) => {
    if (!pubkey || rsvpInProgress) return

    setRsvpInProgress(true)
    try {
      const dTag = event.tags.find(t => t[0] === 'd')?.[1]
      const aTag = `${event.kind}:${event.pubkey}:${dTag}`

      const rsvpEvent = {
        kind: KIND_CALENDAR_RSVP,
        created_at: Math.floor(Date.now() / 1000),
        tags: [
          ['d', `${dTag}-${date}-${pubkey.slice(0, 8)}`],
          ['a', aTag, CALENDAR_RELAYS[0]],
          ['e', event.id, CALENDAR_RELAYS[0]],
          ['p', event.pubkey],
          ['status', status],
          ['date', date],
          ['client', 'nullnull-chousei']
        ],
        content: '',
        pubkey
      }

      const signed = await signEventNip07(rsvpEvent)
      await publishEvent(signed)

      // Update local state
      setRsvps(prev => {
        const filtered = prev.filter(r => {
          if (r.pubkey !== pubkey) return true
          const rDate = r.tags.find(t => t[0] === 'date')?.[1]
          const rA = r.tags.find(t => t[0] === 'a')?.[1]
          return !(rDate === date && rA === aTag)
        })
        return [...filtered, signed]
      })
    } catch (e) {
      console.error('RSVP failed:', e)
      alert('回答の送信に失敗しました')
    } finally {
      setRsvpInProgress(false)
    }
  }

  // Handle event deletion
  const handleDelete = async (event) => {
    if (!pubkey || event.pubkey !== pubkey) return

    try {
      // Create deletion event (NIP-09)
      const dTag = event.tags.find(t => t[0] === 'd')?.[1]
      const aTag = dTag ? `${event.kind}:${event.pubkey}:${dTag}` : null

      const deletionEvent = {
        kind: 5,
        created_at: Math.floor(Date.now() / 1000),
        tags: [
          ['e', event.id]
        ],
        content: 'deleted',
        pubkey
      }

      // Add 'a' tag if available (for addressable events)
      if (aTag) {
        deletionEvent.tags.push(['a', aTag])
      }

      const signed = await signEventNip07(deletionEvent)
      await publishEvent(signed)

      // Remove from local state
      setEvents(prev => prev.filter(e => e.id !== event.id))

      alert('イベントを削除しました')
    } catch (e) {
      console.error('Delete failed:', e)
      alert('削除に失敗しました')
    }
  }

  // Filter events
  const filteredEvents = events.filter(event => {
    // Filter by tab
    let matchesTab = false
    if (activeTab === 'mine') {
      matchesTab = event.pubkey === pubkey
    } else if (activeTab === 'participating') {
      const dTag = event.tags.find(t => t[0] === 'd')?.[1]
      const aTag = dTag ? `${event.kind}:${event.pubkey}:${dTag}` : null
      matchesTab = rsvps.some(r => {
        if (r.pubkey !== pubkey) return false
        // Check both 'a' tag and 'e' tag
        const rsvpATag = r.tags.find(t => t[0] === 'a')?.[1]
        const rsvpETag = r.tags.find(t => t[0] === 'e')?.[1]
        // Match by 'a' tag if both event and RSVP have it
        if (aTag && rsvpATag === aTag) return true
        // Match by 'e' tag
        if (rsvpETag === event.id) return true
        return false
      })
    } else {
      matchesTab = true
    }

    if (!matchesTab) return false

    // Filter out past events - check the latest date
    const dateTags = event.tags.filter(t => t[0] === 'date')
    const optionTags = event.tags.filter(t => t[0] === 'option')
    const startTags = event.tags.filter(t => t[0] === 'start')
    const slotTags = event.tags.filter(t => t[0] === 'slot')
    const candidateTags = event.tags.filter(t => t[0] === 'candidate')

    let eventDates = []
    if (dateTags.length > 0) {
      eventDates = dateTags.map(t => t[1])
    } else if (optionTags.length > 0) {
      eventDates = optionTags.map(t => t[2] || t[1])
    } else if (slotTags.length > 0) {
      eventDates = slotTags.map(t => t[1])
    } else if (candidateTags.length > 0) {
      eventDates = candidateTags.map(t => t[1])
    } else if (startTags.length > 0) {
      eventDates = startTags.map(t => {
        const val = t[1]
        if (val && val.includes('T')) {
          return val.split('T')[0]
        }
        const ts = parseInt(val)
        if (!isNaN(ts) && ts > 1000000000) {
          return new Date(ts * 1000).toISOString().split('T')[0]
        }
        return val
      }).filter(d => d && d !== '1970-01-01')
    }

    // If no dates found, show the event
    if (eventDates.length === 0) return true

    // Check if the latest date has passed
    const today = new Date()
    today.setHours(0, 0, 0, 0)

    const latestDate = eventDates.sort().reverse()[0]
    const eventDate = new Date(latestDate)
    eventDate.setHours(0, 0, 0, 0)

    // Show events with future or today's dates
    return eventDate >= today
  })

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center gap-2">
        <h2 className="text-lg font-bold text-[var(--text-primary)] flex items-center gap-2 flex-1 min-w-0">
          <svg className="w-5 h-5 flex-shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
            <line x1="16" y1="2" x2="16" y2="6"/>
            <line x1="8" y1="2" x2="8" y2="6"/>
            <line x1="3" y1="10" x2="21" y2="10"/>
          </svg>
          <span className="truncate">調整くん</span>
        </h2>
        <button
          type="button"
          onClick={() => setShowSearch(!showSearch)}
          className="p-2 rounded-full bg-[var(--bg-tertiary)] text-[var(--text-secondary)] flex-shrink-0"
        >
          <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="11" cy="11" r="8"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
        </button>
        {pubkey && !showCreateForm && (
          <button
            type="button"
            onClick={() => setShowCreateForm(true)}
            className="px-3 py-2 rounded-full bg-[var(--line-green)] text-white text-sm font-medium whitespace-nowrap flex-shrink-0"
          >
            + 新規
          </button>
        )}
      </div>
      
      {/* Search Panel */}
      {showSearch && (
        <div className="bg-[var(--bg-tertiary)] rounded-xl p-3 space-y-2">
          <div className="flex gap-2">
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="naddr... / chronostr URL"
              className="flex-1 min-w-0 px-3 py-2 rounded-lg bg-[var(--bg-primary)] border border-[var(--border-color)] text-sm text-[var(--text-primary)]"
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            />
            <button
              type="button"
              onClick={handleSearch}
              disabled={searching}
              className="px-3 py-2 rounded-lg bg-[var(--line-green)] text-white text-sm flex-shrink-0"
            >
              {searching ? '...' : '検索'}
            </button>
          </div>
          <p className="text-xs text-[var(--text-tertiary)]">
            chronostrのURLまたはnaddr形式
          </p>
        </div>
      )}
      
      {/* Create Form */}
      {showCreateForm && (
        <div className="bg-[var(--bg-tertiary)] rounded-xl p-4">
          <h3 className="font-semibold text-[var(--text-primary)] mb-4">新しいイベント</h3>
          <CreateEventForm
            pubkey={pubkey}
            onCreated={() => {
              setShowCreateForm(false)
              loadData()
            }}
            onCancel={() => setShowCreateForm(false)}
          />
        </div>
      )}
      
      {/* Tabs */}
      {pubkey && (
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setActiveTab('mine')}
            className={`px-4 py-2 rounded-full text-sm font-medium ${
              activeTab === 'mine'
                ? 'bg-[var(--line-green)] text-white'
                : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]'
            }`}
          >
            自分が作成
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('participating')}
            className={`px-4 py-2 rounded-full text-sm font-medium ${
              activeTab === 'participating'
                ? 'bg-[var(--line-green)] text-white'
                : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]'
            }`}
          >
            参加予定
          </button>
        </div>
      )}
      
      {/* Content */}
      {!pubkey ? (
        <div className="py-8 text-center text-[var(--text-tertiary)]">
          ログインするとイベントを作成できます
        </div>
      ) : loading ? (
        <div className="py-8 text-center text-[var(--text-tertiary)]">
          <div className="w-8 h-8 border-2 border-[var(--line-green)] border-t-transparent rounded-full animate-spin mx-auto mb-2" />
          読み込み中...
        </div>
      ) : filteredEvents.length === 0 ? (
        <div className="py-8 text-center">
          <p className="text-[var(--text-tertiary)]">
            {activeTab === 'mine' ? 'イベントがありません' : '参加予定のイベントがありません'}
          </p>
          {activeTab === 'mine' && (
            <button
              type="button"
              onClick={() => setShowCreateForm(true)}
              className="mt-4 px-6 py-2 rounded-full bg-[var(--line-green)] text-white text-sm"
            >
              最初のイベントを作成
            </button>
          )}
        </div>
      ) : (
        <div className="space-y-3">
          {filteredEvents.map(event => (
            <EventCard
              key={event.id}
              event={event}
              allEvents={allEventsData}
              rsvps={rsvps}
              profiles={profiles}
              myPubkey={pubkey}
              onViewDetails={setSelectedEvent}
            />
          ))}
        </div>
      )}
      
      {/* Refresh button - only show when not loading */}
      {pubkey && !loading && (
        <button
          type="button"
          onClick={loadData}
          className="w-full py-2 rounded-lg bg-[var(--bg-tertiary)] text-[var(--text-secondary)] text-sm flex items-center justify-center gap-2"
        >
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M23 4v6h-6"/>
            <path d="M1 20v-6h6"/>
            <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/>
          </svg>
          更新
        </button>
      )}
      
      {/* Event Detail Modal */}
      {selectedEvent && (
        <EventDetailModal
          event={selectedEvent}
          allEvents={allEventsData}
          rsvps={rsvps}
          profiles={profiles}
          myPubkey={pubkey}
          onClose={() => setSelectedEvent(null)}
          onRsvp={handleRsvp}
          rsvpInProgress={rsvpInProgress}
          onDelete={handleDelete}
        />
      )}
    </div>
  )
}
