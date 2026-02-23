'use client'

import { useState } from 'react'
import {
  requestVanishFromRelay,
  requestGlobalVanish,
  canSign,
  getDefaultRelay,
  FALLBACK_RELAYS
} from '@/lib/nostr'

export default function VanishRequest({ pubkey }) {
  const [vanishMode, setVanishMode] = useState('relay')
  const [vanishRelay, setVanishRelay] = useState('')
  const [vanishReason, setVanishReason] = useState('')
  const [vanishConfirm, setVanishConfirm] = useState('')
  const [vanishLoading, setVanishLoading] = useState(false)
  const [vanishResult, setVanishResult] = useState(null)

  const handleSubmit = async () => {
    if (vanishConfirm !== '削除') {
      alert('確認のため「削除」と入力してください')
      return
    }
    if (vanishMode === 'relay' && !vanishRelay) {
      alert('リレーURLを入力してください')
      return
    }
    if (!canSign()) {
      alert('署名機能が利用できません')
      return
    }

    const confirmMessage = vanishMode === 'global'
      ? '本当に全リレーへ削除リクエストを送信しますか？この操作は取り消せません。'
      : `${vanishRelay} への削除リクエストを送信しますか？`

    if (!confirm(confirmMessage)) return

    setVanishLoading(true)
    setVanishResult(null)

    try {
      let result
      if (vanishMode === 'global') {
        result = await requestGlobalVanish(vanishReason)
      } else {
        result = await requestVanishFromRelay(vanishRelay, vanishReason)
      }

      setVanishResult({
        success: result.success,
        message: result.success
          ? `削除リクエストを送信しました（${vanishMode === 'global' ? '全リレー' : vanishRelay}）`
          : '送信に失敗しました'
      })

      if (result.success) {
        setVanishConfirm('')
        setVanishReason('')
      }
    } catch (e) {
      console.error(e)
      setVanishResult({ success: false, message: 'エラー: ' + e.message })
    } finally {
      setVanishLoading(false)
    }
  }

  return (
    <div className="space-y-4">
      <div className="p-4 bg-[var(--bg-secondary)] rounded-2xl">
        <h3 className="text-lg font-semibold text-red-500 mb-4">削除リクエスト</h3>

        <div className="bg-red-500/10 border border-red-500/30 rounded-xl p-3 mb-4">
          <p className="text-sm text-red-400 font-medium mb-1">⚠️ この操作は取り消せません</p>
          <p className="text-xs text-[var(--text-tertiary)]">
            削除リクエスト(kind 62)を送信すると、対象リレーはあなたの全イベントを削除します。
          </p>
        </div>

        <div className="space-y-4">
          <div className="flex gap-2">
            <button
              onClick={() => setVanishMode('relay')}
              className={`flex-1 py-2 text-sm rounded-lg ${vanishMode === 'relay' ? 'bg-[var(--line-green)] text-white' : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]'}`}
            >
              特定リレー
            </button>
            <button
              onClick={() => setVanishMode('global')}
              className={`flex-1 py-2 text-sm rounded-lg ${vanishMode === 'global' ? 'bg-red-500 text-white' : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]'}`}
            >
              全リレー
            </button>
          </div>

          {vanishMode === 'relay' && (
            <input
              type="text"
              value={vanishRelay}
              onChange={(e) => setVanishRelay(e.target.value)}
              placeholder="wss://..."
              className="w-full input-line text-sm"
            />
          )}

          <textarea
            value={vanishReason}
            onChange={(e) => setVanishReason(e.target.value)}
            placeholder="理由（任意）"
            rows={2}
            className="w-full input-line text-sm resize-none"
          />

          <input
            type="text"
            value={vanishConfirm}
            onChange={(e) => setVanishConfirm(e.target.value)}
            placeholder="確認のため「削除」と入力"
            className="w-full input-line text-sm"
          />

          <button
            onClick={handleSubmit}
            disabled={vanishLoading || vanishConfirm !== '削除'}
            className={`w-full py-3 text-sm font-medium rounded-xl text-white ${vanishMode === 'global' ? 'bg-red-500' : 'bg-orange-500'} disabled:opacity-50`}
          >
            {vanishLoading ? '送信中...' : '削除リクエスト送信'}
          </button>

          {vanishResult && (
            <div className={`p-3 rounded-xl ${vanishResult.success ? 'bg-green-500/10 text-green-400' : 'bg-red-500/10 text-red-400'}`}>
              <p className="text-sm">{vanishResult.message}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
