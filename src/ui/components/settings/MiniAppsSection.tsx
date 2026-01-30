'use client'

import { useState, useEffect } from 'react'

interface MiniApp {
  id: string
  name: string
  type: 'internal' | 'external'
  url?: string
}

interface MiniAppsSectionProps {
  expanded?: boolean
  onToggle?: () => void
  onAppClick?: (app: MiniApp) => void
}

// Available mini apps that can be added to favorites
const AVAILABLE_APPS = [
  { id: 'scheduler', name: '調整くん' },
  { id: 'zap', name: 'Zap設定' },
  { id: 'relay', name: 'リレー設定' },
  { id: 'upload', name: 'アップロード設定' },
  { id: 'mute', name: 'ミュートリスト' },
  { id: 'badge', name: 'プロフィールバッジ' },
  { id: 'emoji', name: 'カスタム絵文字' },
]

export default function MiniAppsSection({ expanded = false, onToggle, onAppClick }: MiniAppsSectionProps) {
  const [showMyApps, setShowMyApps] = useState(expanded)
  const [favoriteApps, setFavoriteApps] = useState<MiniApp[]>([])
  const [externalAppUrl, setExternalAppUrl] = useState('')
  const [externalAppName, setExternalAppName] = useState('')
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null)

  useEffect(() => {
    if (typeof window !== 'undefined') {
      const savedFavorites = localStorage.getItem('favoriteMiniApps')
      if (savedFavorites) {
        try {
          setFavoriteApps(JSON.parse(savedFavorites))
        } catch (e) {
          console.error('Failed to load favorite apps:', e)
        }
      }
    }
  }, [])

  const saveFavoriteApps = (apps: MiniApp[]) => {
    setFavoriteApps(apps)
    localStorage.setItem('favoriteMiniApps', JSON.stringify(apps))
  }

  const handleAddToFavorites = (appId: string, appName: string, appType: 'internal' | 'external' = 'internal') => {
    const newApp: MiniApp = { id: appId, name: appName, type: appType }
    if (!favoriteApps.some(app => app.id === appId)) {
      saveFavoriteApps([...favoriteApps, newApp])
    }
  }

  const handleRemoveFromFavorites = (appId: string) => {
    saveFavoriteApps(favoriteApps.filter(app => app.id !== appId))
  }

  const handleAddExternalApp = () => {
    const url = externalAppUrl.trim()
    if (url && url.startsWith('http')) {
      const appId = 'external_' + Date.now()
      const appName = externalAppName.trim() || new URL(url).hostname
      const newApp: MiniApp = { id: appId, name: appName, type: 'external', url }
      saveFavoriteApps([...favoriteApps, newApp])
      setExternalAppUrl('')
      setExternalAppName('')
    }
  }

  const handleDragStart = (index: number) => {
    setDraggedIndex(index)
  }

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault()
  }

  const handleDrop = (dropIndex: number) => {
    if (draggedIndex === null) return
    const newApps = [...favoriteApps]
    const [removed] = newApps.splice(draggedIndex, 1)
    newApps.splice(dropIndex, 0, removed)
    saveFavoriteApps(newApps)
    setDraggedIndex(null)
  }

  const handleFavoriteAppClick = (app: MiniApp) => {
    if (app.type === 'external' && app.url) {
      window.open(app.url, '_blank', 'noopener,noreferrer')
      return
    }
    onAppClick?.(app)
  }

  const handleToggle = () => {
    setShowMyApps(!showMyApps)
    onToggle?.()
  }

  const availableMiniApps = AVAILABLE_APPS.filter(app => !favoriteApps.some(fav => fav.id === app.id))

  return (
    <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
      <button
        onClick={handleToggle}
        className="w-full flex items-center justify-between"
      >
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/>
          </svg>
          <h2 className="font-semibold text-[var(--text-primary)]">マイミニアプリ</h2>
          {favoriteApps.length > 0 && (
            <span className="text-sm text-[var(--text-tertiary)]">({favoriteApps.length})</span>
          )}
        </div>
        <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showMyApps ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </button>

      {showMyApps && (
        <div className="mt-4 space-y-4">
          {/* Favorited Apps */}
          {favoriteApps.length > 0 && (
            <div>
              <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">お気に入りアプリ</h3>
              <div className="space-y-2">
                {favoriteApps.map((app, index) => (
                  <div
                    key={app.id}
                    draggable
                    onDragStart={() => handleDragStart(index)}
                    onDragOver={handleDragOver}
                    onDrop={() => handleDrop(index)}
                    className="flex items-center justify-between p-3 bg-[var(--bg-tertiary)] rounded-xl hover:bg-[var(--border-color)] transition-colors"
                  >
                    <div
                      className="flex items-center gap-3 flex-1 cursor-pointer"
                      onClick={() => handleFavoriteAppClick(app)}
                    >
                      <svg className="w-4 h-4 text-[var(--text-tertiary)] cursor-move" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <line x1="3" y1="9" x2="21" y2="9"/>
                        <line x1="3" y1="15" x2="21" y2="15"/>
                      </svg>
                      <span className="text-sm text-[var(--text-primary)]">{app.name}</span>
                      {app.type === 'external' && (
                        <span className="text-xs px-2 py-0.5 bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 rounded-full">外部</span>
                      )}
                    </div>
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        handleRemoveFromFavorites(app.id)
                      }}
                      className="text-xs text-red-400 hover:text-red-500 flex-shrink-0"
                    >
                      <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <line x1="18" y1="6" x2="6" y2="18"/>
                        <line x1="6" y1="6" x2="18" y2="18"/>
                      </svg>
                    </button>
                  </div>
                ))}
              </div>
              <p className="text-xs text-[var(--text-tertiary)] mt-2">ドラッグして並び替えができます</p>
            </div>
          )}

          {/* Add Nurunuru Mini App */}
          {availableMiniApps.length > 0 && (
            <div>
              <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ぬるぬるミニアプリを追加</h3>
              <div className="flex flex-wrap gap-2">
                {availableMiniApps.map(app => (
                  <button
                    key={app.id}
                    onClick={() => handleAddToFavorites(app.id, app.name)}
                    className="px-3 py-1.5 text-sm bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-full hover:bg-[var(--line-green)] hover:text-white transition-colors"
                  >
                    + {app.name}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Add External Mini App */}
          <div>
            <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">外部ミニアプリを追加</h3>
            <div className="space-y-2">
              <input
                type="text"
                value={externalAppName}
                onChange={(e) => setExternalAppName(e.target.value)}
                placeholder="アプリ名(例:おいくらサッツ)"
                className="w-full input-line text-sm"
              />
              <div className="flex gap-2">
                <input
                  type="url"
                  value={externalAppUrl}
                  onChange={(e) => setExternalAppUrl(e.target.value)}
                  placeholder="https://..."
                  className="flex-1 input-line text-sm"
                />
                <button
                  onClick={handleAddExternalApp}
                  disabled={!externalAppUrl.trim().startsWith('http')}
                  className="btn-line text-sm px-3 disabled:opacity-50"
                >
                  追加
                </button>
              </div>
            </div>
            <p className="text-xs text-[var(--text-tertiary)] mt-1">名前とURLを入力して外部のミニアプリを追加できます</p>
          </div>

          {favoriteApps.length === 0 && (
            <div className="py-6 text-center text-[var(--text-tertiary)]">
              <p className="text-sm">お気に入りのミニアプリはありません</p>
              <p className="text-xs mt-1">上から追加してください</p>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
