'use client'

import { useState, useEffect } from 'react'

export default function ElevenLabsSettings() {
  const [elevenLabsApiKey, setElevenLabsApiKey] = useState('')
  const [elevenLabsLanguage, setElevenLabsLanguage] = useState('jpn')

  useEffect(() => {
    if (typeof window !== 'undefined') {
      const savedKey = localStorage.getItem('elevenlabs_api_key')
      if (savedKey) setElevenLabsApiKey(savedKey)
      const savedLang = localStorage.getItem('elevenlabs_language')
      if (savedLang) setElevenLabsLanguage(savedLang)
    }
  }, [])

  return (
    <div className="space-y-4">
      <div className="p-4 bg-[var(--bg-secondary)] rounded-2xl">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">ElevenLabs音声入力設定</h3>

        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">APIキー</label>
            <input
              type="password"
              value={elevenLabsApiKey}
              onChange={(e) => {
                setElevenLabsApiKey(e.target.value)
                localStorage.setItem('elevenlabs_api_key', e.target.value)
              }}
              className="w-full input-line text-sm"
              placeholder="xi-api-key..."
            />
            <p className="text-xs text-[var(--text-tertiary)] mt-1">
              ElevenLabsダッシュボードから取得してください
            </p>
          </div>

          <div>
            <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">言語</label>
            <select
              value={elevenLabsLanguage}
              onChange={(e) => {
                setElevenLabsLanguage(e.target.value)
                localStorage.setItem('elevenlabs_language', e.target.value)
              }}
              className="w-full py-2.5 px-3 bg-[var(--bg-primary)] text-[var(--text-primary)] rounded-lg text-sm border border-[var(--border-color)] focus:border-[var(--line-green)] focus:outline-none"
            >
              <option value="jpn">日本語 (Japanese)</option>
              <option value="eng">英語 (English)</option>
              <option value="cmn">中国語 (Chinese)</option>
              <option value="spa">スペイン語 (Spanish)</option>
              <option value="fra">フランス語 (French)</option>
              <option value="deu">ドイツ語 (German)</option>
              <option value="ita">イタリア語 (Italian)</option>
              <option value="por">ポルトガル語 (Portuguese)</option>
              <option value="hin">ヒンディー語 (Hindi)</option>
            </select>
          </div>
        </div>
      </div>
    </div>
  )
}
