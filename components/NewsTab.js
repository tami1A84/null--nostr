'use client'

import { useEffect, useMemo, useState } from 'react'
import { marked } from 'marked'
import { fetchEvents, fetchProfilesBatch, nip19, resolveNip05, parseNostrLink, parseProfile, RELAYS } from '@/lib/nostr'

const NEWS_CATEGORY_NAMESPACE = 'null.news.category'
const NEWS_SOURCE_KEY = 'nurunuru_news_sources'
const CATEGORIES = [
  { id: 'top', label: 'トップ', key: null },
  { id: 'domestic', label: '国内', key: 'domestic' },
  { id: 'entertainment', label: 'エンタメ', key: 'entertainment' },
  { id: 'sports', label: 'スポーツ', key: 'sports' },
  { id: 'economy', label: '経済', key: 'economy' },
  { id: 'tech', label: 'テック', key: 'tech' },
  { id: 'nostr', label: 'Nostr', key: 'nostr' }
]

function getTag(event, name) { return event.tags?.find((tag) => tag?.[0] === name)?.[1] }
function getTags(event, name) { return event.tags?.filter((tag) => tag?.[0] === name).map((tag) => tag?.[1]).filter(Boolean) || [] }
function normalizeCategory(raw) {
  switch ((raw || '').toLowerCase()) {
    case 'top': case 'news': return 'top'
    case 'domestic': case 'japan': case 'jp': case '国内': return 'domestic'
    case 'entertainment': case 'entame': case 'エンタメ': return 'entertainment'
    case 'sports': case 'sport': case 'スポーツ': return 'sports'
    case 'economy': case 'business': case '経済': return 'economy'
    case 'tech': case 'technology': case 'テック': return 'tech'
    case 'nostr': return 'nostr'
    default: return null
  }
}
function extractCategories(event) {
  const labels = (event.tags || []).filter((tag) => tag?.[0] === 'l' && tag?.[2] === NEWS_CATEGORY_NAMESPACE).map((tag) => normalizeCategory(tag[1])).filter(Boolean)
  const hashtags = getTags(event, 't').map(normalizeCategory).filter(Boolean)
  const categories = [...new Set([...labels, ...hashtags])]
  return categories.length > 0 ? categories : ['top']
}
function toArticle(event, profile) {
  const dTag = getTag(event, 'd') || event.id
  const title = getTag(event, 'title') || event.content?.split('\n').find(Boolean)?.slice(0, 80) || '無題の記事'
  const summary = getTag(event, 'summary') || (event.content || '').replace(/\s+/g, ' ').slice(0, 160)
  const publishedAt = Number(getTag(event, 'published_at')) || event.created_at || 0
  const sourceName = profile?.display_name || profile?.displayName || profile?.name || event.pubkey.slice(0, 12) + '...'
  return { id: event.id, address: '30023:' + event.pubkey + ':' + dTag, pubkey: event.pubkey, dTag, title, summary, imageUrl: getTag(event, 'image'), content: event.content || '', publishedAt, createdAt: event.created_at || 0, sourceName, sourcePicture: profile?.picture, categories: extractCategories(event), rawEvent: event }
}
function relativeTime(epochSeconds) {
  const diff = Math.max(0, Math.floor(Date.now() / 1000) - Number(epochSeconds || 0))
  if (diff < 60) return 'たった今'
  if (diff < 3600) return Math.floor(diff / 60) + '分前'
  if (diff < 86400) return Math.floor(diff / 3600) + '時間前'
  return Math.floor(diff / 86400) + '日前'
}
function loadSources() { try { const raw = localStorage.getItem(NEWS_SOURCE_KEY); return raw ? JSON.parse(raw) : [] } catch { return [] } }
function saveSources(sources) { localStorage.setItem(NEWS_SOURCE_KEY, JSON.stringify([...new Set(sources)])) }
async function parseSource(input) {
  const raw = input.trim(); if (!raw) return null
  if (/^[0-9a-f]{64}$/i.test(raw)) return raw.toLowerCase()
  if (raw.startsWith('npub1')) { try { const decoded = nip19.decode(raw); return decoded?.type === 'npub' ? decoded.data : null } catch { return null } }
  return resolveNip05(raw)
}


// NIP-23 markdown: GFM + line breaks, but raw HTML is stripped.
marked.use({
  breaks: true,
  gfm: true,
  renderer: {
    html() { return '' }
  }
})

function sanitizeNewsHtml(html) {
  let clean = String(html || '')
  clean = clean.replace(/<(script|style|iframe|object|embed|form|input|button)[^>]*>[\s\S]*?<\/\1>/gi, '')
  clean = clean.replace(/<(script|style|iframe|object|embed|form|input|button)[^>]*\/?>/gi, '')
  clean = clean.replace(/\s+on\w+\s*=\s*["'][^"']*["']/gi, '')
  clean = clean.replace(/\s+on\w+\s*=\s*\S+/gi, '')
  clean = clean.replace(/href\s*=\s*["']javascript:[^"']*["']/gi, 'href="#"')
  return clean
}

function renderNewsMarkdown(content) {
  try {
    return sanitizeNewsHtml(marked.parse(content || ''))
  } catch {
    return String(content || '')
  }
}

function EmbeddedNewsNote({ parsed }) {
  const [note, setNote] = useState(null)
  const [profile, setProfile] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let mounted = true
    const load = async () => {
      try {
        let events = []
        const relays = parsed.relays?.length ? parsed.relays : RELAYS
        if (parsed.type === 'naddr') {
          events = await fetchEvents({ kinds: [parsed.kind], authors: [parsed.pubkey], '#d': [parsed.identifier], limit: 1 }, relays)
        } else {
          events = await fetchEvents({ ids: [parsed.id], limit: 1 }, relays)
        }
        if (!mounted) return
        if (events.length > 0) {
          setNote(events[0])
          const profiles = await fetchEvents({ kinds: [0], authors: [events[0].pubkey], limit: 1 }, RELAYS)
          if (mounted && profiles.length > 0) setProfile(parseProfile(profiles[0]))
        }
      } finally {
        if (mounted) setLoading(false)
      }
    }
    load()
    return () => { mounted = false }
  }, [parsed])

  if (loading) return <div className="my-4 rounded-xl border border-[var(--border-color)] bg-[var(--bg-secondary)] p-3 text-sm text-[var(--text-tertiary)]">読み込み中…</div>
  if (!note) return <div className="my-4 rounded-xl border border-[var(--border-color)] bg-[var(--bg-secondary)] p-3 text-sm text-[var(--text-tertiary)]">参照を読み込めませんでした</div>
  const name = profile?.display_name || profile?.displayName || profile?.name || note.pubkey.slice(0, 12) + '...'
  return (
    <div className="my-4 rounded-xl border border-[var(--border-color)] bg-[var(--bg-secondary)] p-4">
      <div className="mb-2 flex items-center gap-2 text-sm">
        {profile?.picture ? <img src={profile.picture} alt="" className="h-6 w-6 rounded-full object-cover" referrerPolicy="no-referrer" /> : <div className="h-6 w-6 rounded-full bg-[var(--bg-tertiary)]" />}
        <span className="font-semibold text-[var(--text-primary)]">{name}</span>
        <span className="text-[var(--text-tertiary)]">· {relativeTime(note.created_at)}</span>
      </div>
      <div className="line-clamp-5 whitespace-pre-wrap break-words text-sm leading-6 text-[var(--text-secondary)] [overflow-wrap:anywhere]">{note.content}</div>
    </div>
  )
}

function EmbeddedNewsProfile({ parsed }) {
  const pubkey = parsed.pubkey
  return <span className="font-medium text-[var(--line-green)]">@{pubkey ? pubkey.slice(0, 12) + '...' : 'profile'}</span>
}

function NewsMarkdownWithNostr({ content }) {
  const nostrRegex = /(nostr:)?(?:note1|nevent1|naddr1|npub1|nprofile1)[a-z0-9]+/gi
  const nodes = []
  let lastIndex = 0
  let match
  const text = content || ''

  while ((match = nostrRegex.exec(text)) !== null) {
    const raw = match[0]
    const before = text.slice(lastIndex, match.index)
    if (before) {
      nodes.push(<div key={'md-' + lastIndex} className="long-form-content break-words leading-relaxed text-[var(--text-primary)] [overflow-wrap:anywhere]" dangerouslySetInnerHTML={{ __html: renderNewsMarkdown(before) }} />)
    }
    const bech32 = raw.toLowerCase().startsWith('nostr:') ? raw.slice(6) : raw
    const parsed = parseNostrLink(bech32)
    if (parsed?.type === 'note' || parsed?.type === 'nevent' || parsed?.type === 'naddr') {
      nodes.push(<EmbeddedNewsNote key={'nostr-' + match.index} parsed={parsed} />)
    } else if (parsed?.type === 'npub' || parsed?.type === 'nprofile') {
      nodes.push(<EmbeddedNewsProfile key={'nostr-' + match.index} parsed={parsed} />)
    } else {
      nodes.push(<span key={'nostr-' + match.index} className="break-all text-[var(--line-green)]">{raw}</span>)
    }
    lastIndex = match.index + raw.length
  }

  const rest = text.slice(lastIndex)
  if (rest || nodes.length === 0) {
    nodes.push(<div key={'md-' + lastIndex} className="long-form-content break-words leading-relaxed text-[var(--text-primary)] [overflow-wrap:anywhere]" dangerouslySetInnerHTML={{ __html: renderNewsMarkdown(rest || text) }} />)
  }
  return <div className="space-y-3">{nodes}</div>
}

export default function NewsTab() {
  const [articles, setArticles] = useState([])
  const [selectedCategory, setSelectedCategory] = useState('top')
  const [query, setQuery] = useState('')
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState(null)
  const [sources, setSources] = useState([])
  const [showSources, setShowSources] = useState(false)
  const [sourceInput, setSourceInput] = useState('')
  const [selectedArticle, setSelectedArticle] = useState(null)

  const refresh = async (sourcePubkeys = sources) => {
    setIsLoading(true); setError(null)
    try {
      const filter = { kinds: [30023], limit: 80 }
      if (sourcePubkeys.length > 0) filter.authors = sourcePubkeys
      const events = await fetchEvents(filter)
      const latest = new Map()
      for (const event of events.filter((event) => event.kind === 30023)) {
        const d = getTag(event, 'd') || event.id
        const address = '30023:' + event.pubkey + ':' + d
        const current = latest.get(address)
        if (!current || (event.created_at || 0) > (current.created_at || 0)) latest.set(address, event)
      }
      const profiles = await fetchProfilesBatch([...new Set([...latest.values()].map((event) => event.pubkey))])
      setArticles([...latest.values()].map((event) => toArticle(event, profiles[event.pubkey])).sort((a, b) => b.publishedAt - a.publishedAt))
    } catch (e) { setError(e?.message || 'ニュースを取得できませんでした') } finally { setIsLoading(false) }
  }
  useEffect(() => { const loaded = loadSources(); setSources(loaded); refresh(loaded) }, [])
  const filteredArticles = useMemo(() => {
    const category = CATEGORIES.find((item) => item.id === selectedCategory)
    const byCategory = category?.key ? articles.filter((article) => article.categories.includes(category.key)) : articles
    const q = query.trim().toLowerCase(); if (!q) return byCategory
    return byCategory.filter((a) => a.title.toLowerCase().includes(q) || a.summary.toLowerCase().includes(q) || a.content.toLowerCase().includes(q) || a.sourceName.toLowerCase().includes(q))
  }, [articles, selectedCategory, query])
  const addSource = async () => { const pubkey = await parseSource(sourceInput); if (!pubkey) { setError('ニュースソースを解決できませんでした'); return }; const next = [...new Set([...sources, pubkey])]; setSources(next); saveSources(next); setSourceInput(''); refresh(next) }
  const removeSource = (pubkey) => { const next = sources.filter((source) => source !== pubkey); setSources(next); saveSources(next); refresh(next) }

  return <div className="min-h-full min-w-0 overflow-x-hidden bg-[var(--bg-primary)] text-[var(--text-primary)] pb-4">
    <div className="sticky top-0 z-10 bg-[var(--bg-primary)]/95 backdrop-blur border-b border-[var(--border-color)]">
      <div className="flex items-center px-5 h-14"><h1 className="text-2xl font-bold flex-1">ニュース</h1><button onClick={() => setShowSources(true)} className="p-2 action-btn" aria-label="ニュースソース設定">☰</button></div>
      <div className="px-5 pb-3"><div className="flex items-center gap-2 h-11 px-4 rounded-full bg-[var(--bg-secondary)]"><span className="text-[var(--text-tertiary)]">⌕</span><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="検索" className="flex-1 bg-transparent outline-none text-sm" /></div></div>
      <div className="overflow-x-auto px-5 flex gap-6">{CATEGORIES.map((category) => { const active = selectedCategory === category.id; return <button key={category.id} onClick={() => setSelectedCategory(category.id)} className="pt-1 whitespace-nowrap"><div className={'text-base ' + (active ? 'font-bold' : 'font-medium')}>{category.label}</div><div className={'mx-auto mt-2 h-[3px] rounded-full bg-[var(--text-primary)] ' + (active ? 'w-10' : 'w-0')} /></button> })}</div>
    </div>
    {error && <div className="mx-5 mt-4 text-sm text-red-400">{error}</div>}
    {isLoading && articles.length === 0 ? <div className="flex justify-center py-20"><div className="loading-spinner" /></div> : filteredArticles.length === 0 ? <div className="px-6 py-20 text-center text-[var(--text-secondary)]">{sources.length === 0 ? 'NIP-23 の最新記事が見つかりません' : '設定したニュースソースの記事が見つかりません'}</div> : <div className="p-5 space-y-6">{filteredArticles.map((article) => <button key={article.id} onClick={() => setSelectedArticle(article)} className="block w-full text-left overflow-hidden rounded-xl bg-[var(--bg-secondary)] action-btn">{article.imageUrl ? <img src={article.imageUrl} alt="" className="w-full h-52 object-cover" /> : <div className="w-full h-28 flex items-center justify-center bg-[var(--bg-tertiary)] text-[var(--text-tertiary)] font-bold">NEWS</div>}<div className="p-4"><h2 className="text-xl leading-8 font-bold line-clamp-3 break-words [overflow-wrap:anywhere]">{article.title}</h2><div className="mt-2 text-sm text-[var(--text-tertiary)]">{article.sourceName} ・ {relativeTime(article.publishedAt)}</div></div></button>)}</div>}
    {showSources && <div className="fixed inset-0 lg:left-[240px] xl:left-[280px] z-[100] bg-[var(--bg-primary)] p-5 overflow-x-hidden overflow-y-auto"><div className="flex items-center h-12"><h2 className="text-xl font-bold flex-1">ニュースソース設定</h2><button onClick={() => setShowSources(false)} className="p-2 action-btn">✕</button></div><p className="mt-2 text-sm text-[var(--text-secondary)]">npub / hex / NIP-05 を追加できます。未設定時はリレー上の最新記事を表示します。</p><div className="mt-4 flex gap-2"><input value={sourceInput} onChange={(e) => setSourceInput(e.target.value)} placeholder="npub1... / user@example.com" className="flex-1 px-3 py-2 rounded-lg bg-[var(--bg-secondary)] outline-none" /><button onClick={addSource} className="px-4 py-2 rounded-lg bg-[var(--accent-color)] text-black font-bold">追加</button></div><div className="mt-6 divide-y divide-[var(--border-color)]">{sources.map((source) => <div key={source} className="flex items-center py-3"><span className="min-w-0 flex-1 text-sm font-mono truncate">{source.slice(0, 12)}...</span><button onClick={() => removeSource(source)} className="text-red-400 text-sm">削除</button></div>)}</div></div>}
    {selectedArticle && <div className="fixed inset-0 lg:left-[240px] xl:left-[280px] z-[100] bg-[var(--bg-primary)] overflow-x-hidden overflow-y-auto"><div className="sticky top-0 flex items-center h-14 px-3 bg-[var(--bg-primary)] border-b border-[var(--border-color)]"><button onClick={() => setSelectedArticle(null)} className="p-2 action-btn">✕</button><div className="font-bold flex-1 text-center pr-10">ニュース</div></div><article className="mx-auto max-w-3xl p-5 space-y-4"><h1 className="text-2xl leading-9 font-bold break-words [overflow-wrap:anywhere]">{selectedArticle.title}</h1><div className="text-sm text-[var(--text-tertiary)]">{selectedArticle.sourceName} ・ {relativeTime(selectedArticle.publishedAt)}</div>{selectedArticle.imageUrl && <img src={selectedArticle.imageUrl} alt="" className="w-full h-56 object-cover rounded-xl" />}<NewsMarkdownWithNostr content={selectedArticle.content} /></article></div>}
  </div>
}
