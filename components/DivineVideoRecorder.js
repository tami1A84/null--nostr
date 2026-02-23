'use client'

import React, { useRef, useState, useEffect, useCallback } from 'react'
import { uploadToBlossom } from '@/lib/nostr'
import { generateProofModeTags } from '@/lib/proofmode'

const RECORDING_LIMIT_MS = 6300

export default function DivineVideoRecorder({ onComplete, onClose }) {
  const videoRef = useRef(null)
  const mediaRecorderRef = useRef(null)
  const chunksRef = useRef([])
  const progressIntervalRef = useRef(null)
  const startTimeRef = useRef(0)
  const streamRef = useRef(null)

  const [stream, setStream] = useState(null)
  const [facingMode, setFacingMode] = useState('user') // 'user' or 'environment'
  const [isReady, setIsReady] = useState(false)
  const [isRecording, setIsRecording] = useState(false)
  const [progress, setProgress] = useState(0)
  const [isUploading, setIsUploading] = useState(false)
  const [uploadStatus, setUploadStatus] = useState('')

  // Initialize camera
  useEffect(() => {
    async function setupCamera() {
      // Stop existing tracks before starting new ones
      if (streamRef.current) {
        streamRef.current.getTracks().forEach(track => track.stop())
        streamRef.current = null
      }

      const constraintsList = [
        // 1. High quality square
        {
          video: {
            facingMode: { ideal: facingMode },
            width: { ideal: 720 },
            height: { ideal: 720 },
            aspectRatio: { ideal: 1 }
          },
          audio: true
        },
        // 2. Standard quality
        {
          video: { facingMode: { ideal: facingMode } },
          audio: true
        },
        // 3. High quality square (no audio)
        {
          video: {
            facingMode: { ideal: facingMode },
            width: { ideal: 720 },
            height: { ideal: 720 },
            aspectRatio: { ideal: 1 }
          },
          audio: false
        },
        // 4. Standard quality (no audio)
        {
          video: { facingMode: { ideal: facingMode } },
          audio: false
        },
        // 5. Basic video + audio
        {
          video: true,
          audio: true
        },
        // 6. Basic video only
        {
          video: true,
          audio: false
        }
      ]

      let lastError = null
      for (const constraints of constraintsList) {
        try {
          console.log('Trying camera constraints:', constraints)
          const s = await navigator.mediaDevices.getUserMedia(constraints)

          // Store in ref and state
          streamRef.current = s
          setStream(s)

          if (videoRef.current) {
            videoRef.current.srcObject = s
          }
          setIsReady(true)
          return // Success!
        } catch (err) {
          console.warn('Constraints failed:', constraints, err.name, err.message)
          lastError = err
        }
      }

      console.error('All camera access strategies failed:', lastError)
      alert(`カメラへのアクセスに失敗しました: ${lastError?.name} - ${lastError?.message || '不明なエラー'}\n\n原因として以下が考えられます:\n1. カメラ・マイクの権限が許可されていない\n2. 他のアプリがカメラを使用中\n3. デバイスにカメラが搭載されていない\n\n設定を確認してから再度お試しください。`)
      onClose()
    }
    setupCamera()
    return () => {
      // Robust cleanup using ref
      if (streamRef.current) {
        streamRef.current.getTracks().forEach(track => {
          track.stop()
          console.log('Camera track stopped:', track.kind)
        })
        streamRef.current = null
      }
      if (videoRef.current) {
        videoRef.current.srcObject = null
      }
      if (progressIntervalRef.current) clearInterval(progressIntervalRef.current)
    }
  }, [facingMode])

  const toggleCamera = useCallback((e) => {
    e.stopPropagation()
    if (isRecording || isUploading) return
    setFacingMode(prev => prev === 'user' ? 'environment' : 'user')
  }, [isRecording, isUploading])

  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
      mediaRecorderRef.current.stop()
      setIsRecording(false)
      if (progressIntervalRef.current) {
        clearInterval(progressIntervalRef.current)
        progressIntervalRef.current = null
      }
    }
  }, [])

  const startRecording = useCallback((e) => {
    e.preventDefault()
    if (!stream || isRecording || isUploading) return

    chunksRef.current = []

    // Select supported mime type
    let mimeType = 'video/mp4'
    if (MediaRecorder.isTypeSupported('video/webm;codecs=vp9,opus')) {
      mimeType = 'video/webm;codecs=vp9,opus'
    } else if (MediaRecorder.isTypeSupported('video/webm')) {
      mimeType = 'video/webm'
    }

    try {
      const recorder = new MediaRecorder(stream, { mimeType })
      mediaRecorderRef.current = recorder

      recorder.ondataavailable = (ev) => {
        if (ev.data.size > 0) chunksRef.current.push(ev.data)
      }

      recorder.onstop = async () => {
        const blob = new Blob(chunksRef.current, { type: mimeType })
        if (blob.size > 0) {
          await handleVideoReady(blob)
        }
      }

      recorder.start()
      setIsRecording(true)
      setProgress(0)
      startTimeRef.current = Date.now()

      progressIntervalRef.current = setInterval(() => {
        const elapsed = Date.now() - startTimeRef.current
        const p = Math.min((elapsed / RECORDING_LIMIT_MS) * 100, 100)
        setProgress(p)
        if (elapsed >= RECORDING_LIMIT_MS) {
          stopRecording()
        }
      }, 30)
    } catch (err) {
      console.error('Failed to start recording:', err)
      alert('録画の開始に失敗しました')
    }
  }, [stream, isRecording, isUploading, stopRecording])

  const handleVideoReady = async (blob) => {
    try {
      setIsUploading(true)
      setUploadStatus('真正性を証明中...')

      // 1. Generate ProofMode tags
      const proofTags = await generateProofModeTags(blob)

      setUploadStatus('動画をアップロード中...')
      // 2. Upload to Divine Blossom server
      const file = new File([blob], 'divine_loop.mp4', { type: blob.type })
      const videoUrl = await uploadToBlossom(file, 'https://media.divine.video')

      onComplete({
        url: videoUrl,
        proofTags,
        mimeType: blob.type,
        size: blob.size,
        dim: '720x720'
      })
    } catch (err) {
      console.error('Video processing failed:', err)
      alert('動画の処理に失敗しました: ' + err.message)
      setIsUploading(false)
      setProgress(0)
    }
  }

  return (
    <div className="fixed inset-0 z-[70] flex flex-col items-center justify-center bg-black animate-fadeIn">
      {/* Header */}
      <div className="absolute top-0 left-0 right-0 p-4 flex justify-between items-center z-10">
        <button
          onClick={(e) => { e.stopPropagation(); onClose(); }}
          className="text-white p-2 hover:bg-white/10 rounded-full transition-colors"
        >
           <svg className="w-8 h-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
        <div className="text-white font-bold text-lg">6.3秒ループ動画</div>
        <button
          onClick={toggleCamera}
          disabled={isRecording || isUploading}
          className="text-white p-2 hover:bg-white/10 rounded-full transition-colors disabled:opacity-30"
          title="カメラ切り替え"
        >
          <svg className="w-7 h-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
            <circle cx="12" cy="13" r="4" />
            <path d="M16 8h.01" />
          </svg>
        </button>
      </div>

      {/* Preview Area */}
      <div className="relative w-full aspect-square max-w-[500px] overflow-hidden bg-[#111] shadow-2xl">
        <video
          ref={videoRef}
          autoPlay
          muted
          playsInline
          className="w-full h-full object-cover"
          style={{ transform: facingMode === 'user' ? 'scaleX(-1)' : 'none' }}
        />

        {/* Progress Bar Overlay */}
        <div className="absolute top-0 left-0 right-0 h-2 bg-white/20">
          <div
            className="h-full bg-[var(--line-green)] transition-all duration-75"
            style={{ width: `${progress}%` }}
          />
        </div>

        {/* Uploading Overlay */}
        {isUploading && (
          <div className="absolute inset-0 bg-black/70 flex flex-col items-center justify-center text-white p-6 text-center z-20">
            <div className="w-14 h-14 border-4 border-[var(--line-green)] border-t-transparent rounded-full animate-spin mb-6" />
            <div className="font-bold text-xl mb-2">{uploadStatus}</div>
            <p className="text-white/60 text-sm">しばらくお待ちください...</p>
          </div>
        )}
      </div>

      {/* Footer / Controls */}
      <div className="flex-1 w-full flex flex-col items-center justify-center p-8 pb-20 bg-gradient-to-b from-transparent to-black/50">
        {!isUploading && (
          <>
            <p className="text-white/70 mb-10 text-base font-medium">ボタンを長押しして録画</p>

            <button
              onMouseDown={startRecording}
              onMouseUp={stopRecording}
              onMouseLeave={stopRecording}
              onTouchStart={startRecording}
              onTouchEnd={stopRecording}
              className={`w-24 h-24 rounded-full border-[6px] transition-all duration-200 flex items-center justify-center select-none active:scale-95 ${
                isRecording
                  ? 'border-[var(--line-green)] scale-110 shadow-[0_0_30px_rgba(6,199,85,0.4)]'
                  : 'border-white/80'
              }`}
            >
              <div className={`transition-all duration-300 ${
                isRecording
                  ? 'w-10 h-10 bg-[var(--line-green)] rounded-lg'
                  : 'w-18 h-18 bg-red-600 rounded-full'
              }`} />
            </button>
            <div className="mt-8 text-white/40 text-sm">
              {isRecording ? '録画中...' : `最大 ${RECORDING_LIMIT_MS / 1000}秒`}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
