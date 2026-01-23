'use client'

import { useState, useRef } from 'react'
import {
  fetchAllUserEvents,
  exportEventsToJson,
  parseEventsFromJson,
  importEventsToRelays,
  isProtectedEvent,
  getDefaultRelay
} from '@/lib/nostr'

// Event kind labels for display
const KIND_LABELS = {
  0: 'プロフィール',
  1: 'テキスト投稿',
  3: 'フォローリスト',
  5: '削除',
  6: 'リポスト',
  7: 'リアクション',
  10000: 'ミュートリスト',
  10002: 'リレーリスト',
  30023: '長文記事',
}

function getKindLabel(kind) {
  return KIND_LABELS[kind] || `kind: ${kind}`
}

export default function EventBackupApp({ pubkey }) {
  // Export state
  const [exporting, setExporting] = useState(false)
  const [exportProgress, setExportProgress] = useState(null)
  const [exportedEvents, setExportedEvents] = useState([])
  const [exportError, setExportError] = useState(null)

  // Import state
  const [importing, setImporting] = useState(false)
  const [importProgress, setImportProgress] = useState(null)
  const [importResult, setImportResult] = useState(null)
  const [importError, setImportError] = useState(null)
  const [parsedEvents, setParsedEvents] = useState([])
  const fileInputRef = useRef(null)

  // UI state
  const [activeTab, setActiveTab] = useState('export') // 'export' or 'import'

  // Export all events
  const handleExport = async () => {
    if (!pubkey) {
      setExportError('ログインが必要です')
      return
    }

    setExporting(true)
    setExportError(null)
    setExportProgress({ fetched: 0, batch: 0 })
    setExportedEvents([])

    try {
      const events = await fetchAllUserEvents(pubkey, {
        onProgress: (progress) => {
          setExportProgress(progress)
        }
      })

      setExportedEvents(events)
      setExportProgress(null)

      if (events.length === 0) {
        setExportError('エクスポートするイベントがありません')
      }
    } catch (e) {
      console.error('Export failed:', e)
      setExportError('エクスポートに失敗しました: ' + e.message)
    } finally {
      setExporting(false)
    }
  }

  // Download events as JSON file
  const handleDownload = () => {
    if (exportedEvents.length === 0) return

    const jsonString = exportEventsToJson(exportedEvents)
    const blob = new Blob([jsonString], { type: 'application/json' })
    const url = URL.createObjectURL(blob)

    const a = document.createElement('a')
    a.href = url
    a.download = `nostr-backup-${pubkey.slice(0, 8)}-${new Date().toISOString().split('T')[0]}.json`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  // Handle file selection for import
  const handleFileSelect = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return

    setImportError(null)
    setParsedEvents([])
    setImportResult(null)

    try {
      const text = await file.text()
      const events = parseEventsFromJson(text)

      if (events.length === 0) {
        setImportError('有効なイベントが見つかりませんでした')
        return
      }

      // Count protected events
      const protectedCount = events.filter(e => isProtectedEvent(e)).length
      const myEvents = events.filter(e => e.pubkey === pubkey)

      setParsedEvents(events)

      if (protectedCount > 0) {
        console.log(`${protectedCount} protected events found`)
      }
      if (myEvents.length < events.length) {
        console.log(`${events.length - myEvents.length} events from other users`)
      }
    } catch (e) {
      console.error('Parse failed:', e)
      setImportError('ファイルの読み込みに失敗しました: ' + e.message)
    }
  }

  // Import events to relay
  const handleImport = async () => {
    if (parsedEvents.length === 0) return

    setImporting(true)
    setImportError(null)
    setImportResult(null)
    setImportProgress({ current: 0, total: parsedEvents.length, success: 0, failed: 0 })

    try {
      const result = await importEventsToRelays(
        parsedEvents,
        [getDefaultRelay()],
        (progress) => {
          setImportProgress(progress)
        }
      )

      setImportResult(result)
      setImportProgress(null)
    } catch (e) {
      console.error('Import failed:', e)
      setImportError('インポートに失敗しました: ' + e.message)
    } finally {
      setImporting(false)
    }
  }

  // Clear import state
  const handleClearImport = () => {
    setParsedEvents([])
    setImportResult(null)
    setImportError(null)
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  // Get event statistics by kind
  const getEventStats = (events) => {
    const stats = {}
    for (const event of events) {
      stats[event.kind] = (stats[event.kind] || 0) + 1
    }
    return Object.entries(stats)
      .sort((a, b) => b[1] - a[1])
      .map(([kind, count]) => ({ kind: parseInt(kind), count }))
  }

  return (
    <div className="space-y-4">
      {/* Tab Selector */}
      <div className="flex border-b border-[var(--border-color)]">
        <button
          onClick={() => setActiveTab('export')}
          className={`flex-1 py-2 text-sm font-medium transition-colors ${
            activeTab === 'export'
              ? 'text-[var(--line-green)] border-b-2 border-[var(--line-green)]'
              : 'text-[var(--text-tertiary)]'
          }`}
        >
          エクスポート
        </button>
        <button
          onClick={() => setActiveTab('import')}
          className={`flex-1 py-2 text-sm font-medium transition-colors ${
            activeTab === 'import'
              ? 'text-[var(--line-green)] border-b-2 border-[var(--line-green)]'
              : 'text-[var(--text-tertiary)]'
          }`}
        >
          インポート
        </button>
      </div>

      {/* Export Tab */}
      {activeTab === 'export' && (
        <div className="space-y-4">
          <p className="text-sm text-[var(--text-tertiary)]">
            すべてのイベントをJSON形式でバックアップできます。
          </p>

          {/* Export Button */}
          {exportedEvents.length === 0 && !exporting && (
            <button
              onClick={handleExport}
              disabled={!pubkey}
              className="w-full py-3 bg-[var(--line-green)] text-white rounded-xl text-sm font-medium disabled:opacity-50"
            >
              イベントを取得
            </button>
          )}

          {/* Export Progress */}
          {exporting && exportProgress && (
            <div className="p-4 bg-[var(--bg-tertiary)] rounded-xl">
              <div className="flex items-center gap-3">
                <svg className="w-5 h-5 animate-spin text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                  <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                </svg>
                <div>
                  <p className="text-sm text-[var(--text-primary)]">
                    イベントを取得中...
                  </p>
                  <p className="text-xs text-[var(--text-tertiary)]">
                    {exportProgress.fetched}件取得 (バッチ {exportProgress.batch})
                  </p>
                </div>
              </div>
            </div>
          )}

          {/* Export Error */}
          {exportError && (
            <div className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl">
              <p className="text-sm text-red-600 dark:text-red-400">{exportError}</p>
            </div>
          )}

          {/* Exported Events Summary */}
          {exportedEvents.length > 0 && (
            <div className="space-y-3">
              <div className="p-4 bg-[var(--bg-tertiary)] rounded-xl">
                <p className="text-sm font-medium text-[var(--text-primary)] mb-2">
                  {exportedEvents.length}件のイベント
                </p>

                {/* Event stats by kind */}
                <div className="space-y-1">
                  {getEventStats(exportedEvents).slice(0, 8).map(({ kind, count }) => (
                    <div key={kind} className="flex justify-between text-xs">
                      <span className="text-[var(--text-tertiary)]">{getKindLabel(kind)}</span>
                      <span className="text-[var(--text-secondary)]">{count}</span>
                    </div>
                  ))}
                  {getEventStats(exportedEvents).length > 8 && (
                    <p className="text-xs text-[var(--text-tertiary)]">
                      ...他 {getEventStats(exportedEvents).length - 8} 種類
                    </p>
                  )}
                </div>

                {/* Protected events count */}
                {exportedEvents.filter(e => isProtectedEvent(e)).length > 0 && (
                  <p className="text-xs text-[var(--text-tertiary)] mt-2">
                    {exportedEvents.filter(e => isProtectedEvent(e)).length}件の保護イベント (NIP-70) を含む
                  </p>
                )}
              </div>

              {/* Download Button */}
              <button
                onClick={handleDownload}
                className="w-full py-3 bg-[var(--line-green)] text-white rounded-xl text-sm font-medium flex items-center justify-center gap-2"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                  <polyline points="7 10 12 15 17 10"/>
                  <line x1="12" y1="15" x2="12" y2="3"/>
                </svg>
                JSONファイルをダウンロード
              </button>

              {/* Re-fetch Button */}
              <button
                onClick={() => setExportedEvents([])}
                className="w-full py-2 text-sm text-[var(--text-tertiary)] hover:text-[var(--text-secondary)]"
              >
                再取得
              </button>
            </div>
          )}
        </div>
      )}

      {/* Import Tab */}
      {activeTab === 'import' && (
        <div className="space-y-4">
          <p className="text-sm text-[var(--text-tertiary)]">
            JSONファイルからイベントをインポートできます。自分のイベントのみがリレーに送信されます。
          </p>

          {/* File Input */}
          {parsedEvents.length === 0 && !importing && (
            <div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".json,application/json"
                onChange={handleFileSelect}
                className="hidden"
                id="event-backup-file"
              />
              <label
                htmlFor="event-backup-file"
                className="block w-full py-8 border-2 border-dashed border-[var(--border-color)] rounded-xl text-center cursor-pointer hover:border-[var(--line-green)] transition-colors"
              >
                <svg className="w-8 h-8 mx-auto mb-2 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                  <polyline points="17 8 12 3 7 8"/>
                  <line x1="12" y1="3" x2="12" y2="15"/>
                </svg>
                <p className="text-sm text-[var(--text-secondary)]">
                  クリックしてファイルを選択
                </p>
                <p className="text-xs text-[var(--text-tertiary)] mt-1">
                  JSON形式のバックアップファイル
                </p>
              </label>
            </div>
          )}

          {/* Import Error */}
          {importError && (
            <div className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl">
              <p className="text-sm text-red-600 dark:text-red-400">{importError}</p>
            </div>
          )}

          {/* Parsed Events Preview */}
          {parsedEvents.length > 0 && !importResult && (
            <div className="space-y-3">
              <div className="p-4 bg-[var(--bg-tertiary)] rounded-xl">
                <p className="text-sm font-medium text-[var(--text-primary)] mb-2">
                  {parsedEvents.length}件のイベント
                </p>

                {/* Event stats by kind */}
                <div className="space-y-1">
                  {getEventStats(parsedEvents).slice(0, 6).map(({ kind, count }) => (
                    <div key={kind} className="flex justify-between text-xs">
                      <span className="text-[var(--text-tertiary)]">{getKindLabel(kind)}</span>
                      <span className="text-[var(--text-secondary)]">{count}</span>
                    </div>
                  ))}
                </div>

                {/* Ownership warning */}
                {parsedEvents.some(e => e.pubkey !== pubkey) && (
                  <div className="mt-2 p-2 bg-yellow-50 dark:bg-yellow-900/20 rounded-lg">
                    <p className="text-xs text-yellow-600 dark:text-yellow-400">
                      {parsedEvents.filter(e => e.pubkey !== pubkey).length}件は他のユーザーのイベントです（インポート時にスキップされます）
                    </p>
                  </div>
                )}

                {/* Protected events info */}
                {parsedEvents.some(e => isProtectedEvent(e)) && (
                  <p className="text-xs text-[var(--text-tertiary)] mt-2">
                    {parsedEvents.filter(e => isProtectedEvent(e)).length}件の保護イベント (NIP-70)
                  </p>
                )}
              </div>

              {/* Import Progress */}
              {importing && importProgress && (
                <div className="p-4 bg-[var(--bg-tertiary)] rounded-xl">
                  <div className="flex items-center gap-3 mb-2">
                    <svg className="w-5 h-5 animate-spin text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                      <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                    </svg>
                    <p className="text-sm text-[var(--text-primary)]">
                      インポート中...
                    </p>
                  </div>
                  <div className="w-full h-2 bg-[var(--bg-secondary)] rounded-full overflow-hidden">
                    <div
                      className="h-full bg-[var(--line-green)] transition-all duration-200"
                      style={{ width: `${(importProgress.current / importProgress.total) * 100}%` }}
                    />
                  </div>
                  <p className="text-xs text-[var(--text-tertiary)] mt-1">
                    {importProgress.current} / {importProgress.total} ({importProgress.success}件成功)
                  </p>
                </div>
              )}

              {/* Import Buttons */}
              {!importing && (
                <div className="flex gap-2">
                  <button
                    onClick={handleImport}
                    className="flex-1 py-3 bg-[var(--line-green)] text-white rounded-xl text-sm font-medium"
                  >
                    インポート開始
                  </button>
                  <button
                    onClick={handleClearImport}
                    className="px-4 py-3 bg-[var(--bg-tertiary)] text-[var(--text-secondary)] rounded-xl text-sm"
                  >
                    キャンセル
                  </button>
                </div>
              )}
            </div>
          )}

          {/* Import Result */}
          {importResult && (
            <div className="space-y-3">
              <div className="p-4 bg-[var(--bg-tertiary)] rounded-xl">
                <p className="text-sm font-medium text-[var(--text-primary)] mb-3">
                  インポート完了
                </p>

                <div className="grid grid-cols-3 gap-2 text-center">
                  <div className="p-2 bg-green-50 dark:bg-green-900/20 rounded-lg">
                    <p className="text-lg font-bold text-green-600 dark:text-green-400">
                      {importResult.success}
                    </p>
                    <p className="text-xs text-green-600 dark:text-green-400">成功</p>
                  </div>
                  <div className="p-2 bg-red-50 dark:bg-red-900/20 rounded-lg">
                    <p className="text-lg font-bold text-red-600 dark:text-red-400">
                      {importResult.failed}
                    </p>
                    <p className="text-xs text-red-600 dark:text-red-400">失敗</p>
                  </div>
                  <div className="p-2 bg-gray-50 dark:bg-gray-900/20 rounded-lg">
                    <p className="text-lg font-bold text-gray-600 dark:text-gray-400">
                      {importResult.skipped}
                    </p>
                    <p className="text-xs text-gray-600 dark:text-gray-400">スキップ</p>
                  </div>
                </div>

                {importResult.skipped > 0 && (
                  <p className="text-xs text-[var(--text-tertiary)] mt-2">
                    保護イベント（NIP-70）または他のユーザーのイベントはスキップされました
                  </p>
                )}
              </div>

              <button
                onClick={handleClearImport}
                className="w-full py-3 bg-[var(--bg-tertiary)] text-[var(--text-secondary)] rounded-xl text-sm"
              >
                別のファイルをインポート
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
