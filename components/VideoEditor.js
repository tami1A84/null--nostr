'use client'

import { useState, useRef, useEffect, useCallback } from 'react'

const MAX_CLIP_DURATION = 6 // seconds

/**
 * Video editor for trimming videos to 6-second clips (diVine-compatible format)
 *
 * Props:
 *   file: File - the original video file
 *   onDone: (trimmedFile: File, meta: { duration, width, height }) => void
 *   onCancel: () => void
 */
export default function VideoEditor({ file, onDone, onCancel }) {
  const videoRef = useRef(null)
  const trackRef = useRef(null)
  const animFrameRef = useRef(null)
  const trimmingRef = useRef(false)
  const videoUrlRef = useRef(null)

  const [duration, setDuration] = useState(0)
  const [trimStart, setTrimStart] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [videoUrl, setVideoUrl] = useState(null)
  const [videoSize, setVideoSize] = useState({ width: 0, height: 0 })
  const [trimming, setTrimming] = useState(false)
  const [trimProgress, setTrimProgress] = useState(0)
  const [currentTime, setCurrentTime] = useState(0)

  // Clip end time
  const clipDuration = Math.min(MAX_CLIP_DURATION, duration)
  const trimEnd = Math.min(trimStart + clipDuration, duration)

  // Create object URL with delayed revocation to survive React Strict Mode.
  // Strict Mode runs setup → cleanup → setup. Immediate revoke in cleanup
  // invalidates the URL while the video is still loading → AbortError in Firefox.
  // setTimeout defers revocation until after the re-mount creates a new URL.
  useEffect(() => {
    if (!file) return

    const newUrl = URL.createObjectURL(file)
    videoUrlRef.current = newUrl
    setVideoUrl(newUrl)

    return () => {
      // Delay revocation so the browser has time to finish fetching
      // before the URL is invalidated (prevents AbortError in Strict Mode)
      setTimeout(() => {
        URL.revokeObjectURL(newUrl)
      }, 1000)
    }
  }, [file])

  // When first frame data loaded - force render of the first frame
  const handleLoadedData = () => {
    const video = videoRef.current
    if (!video) return
    setDuration(video.duration)
    setVideoSize({ width: video.videoWidth, height: video.videoHeight })
    // Seek to near-zero to force the browser to decode and display the first frame
    // (currentTime=0 may be a no-op if already at 0, so use a tiny offset)
    video.currentTime = 0.001
  }

  // Playback loop within trim range
  const updatePlayback = useCallback(() => {
    const video = videoRef.current
    if (!video || !playing) return

    if (video.currentTime >= trimEnd) {
      video.currentTime = trimStart
    }
    setCurrentTime(video.currentTime)
    animFrameRef.current = requestAnimationFrame(updatePlayback)
  }, [playing, trimStart, trimEnd])

  useEffect(() => {
    if (playing) {
      animFrameRef.current = requestAnimationFrame(updatePlayback)
    }
    return () => {
      if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current)
    }
  }, [playing, updatePlayback])

  // Play/pause toggle
  const togglePlay = () => {
    const video = videoRef.current
    if (!video) return

    if (playing) {
      video.pause()
      setPlaying(false)
    } else {
      if (video.currentTime < trimStart || video.currentTime >= trimEnd) {
        video.currentTime = trimStart
      }
      video.play()
      setPlaying(true)
    }
  }

  // Handle time update - loop within trim range (only during preview, not trimming)
  const handleTimeUpdate = () => {
    const video = videoRef.current
    if (!video) return
    setCurrentTime(video.currentTime)
    // Only loop during preview playback, not during trim recording
    if (!trimmingRef.current && video.currentTime >= trimEnd) {
      video.currentTime = trimStart
    }
  }

  // Drag trim handle
  const handleTrackInteraction = (e) => {
    const track = trackRef.current
    if (!track || duration <= 0 || trimming) return

    e.preventDefault()
    const updateFromEvent = (evt) => {
      const rect = track.getBoundingClientRect()
      const clientX = evt.touches ? evt.touches[0].clientX : evt.clientX
      const ratio = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width))
      const time = ratio * duration
      const newStart = Math.max(0, Math.min(time - clipDuration / 2, duration - clipDuration))
      setTrimStart(newStart)
      if (videoRef.current) {
        videoRef.current.currentTime = newStart
        setCurrentTime(newStart)
      }
    }

    updateFromEvent(e)

    const handleMove = (evt) => {
      evt.preventDefault()
      updateFromEvent(evt)
    }

    const handleEnd = () => {
      document.removeEventListener('mousemove', handleMove)
      document.removeEventListener('mouseup', handleEnd)
      document.removeEventListener('touchmove', handleMove)
      document.removeEventListener('touchend', handleEnd)
    }

    document.addEventListener('mousemove', handleMove)
    document.addEventListener('mouseup', handleEnd)
    document.addEventListener('touchmove', handleMove, { passive: false })
    document.addEventListener('touchend', handleEnd)
  }

  // Trim using the existing preview video element (already loaded, in DOM)
  const handleDone = async () => {
    const video = videoRef.current
    if (!video) return

    // Stop preview playback
    video.pause()
    setPlaying(false)

    // No trim needed for short videos
    if (duration <= MAX_CLIP_DURATION) {
      onDone(file, {
        duration: Math.round(duration),
        width: videoSize.width,
        height: videoSize.height
      })
      return
    }

    setTrimming(true)
    trimmingRef.current = true
    setTrimProgress(0)

    try {
      // Pre-authorize video playback within user gesture context.
      // Firefox requires a user-gesture-initiated play() before allowing
      // programmatic play() from async callbacks (like 'seeked').
      // We wait for play() to resolve (playback actually started) then pause,
      // so the browser marks this element as "allowed to play".
      try {
        const p = video.play()
        if (p) await p
      } catch {
        // Ignore - may fail if video is at end, etc.
      }
      video.pause()

      const trimmedBlob = await recordFromVideoElement(
        video,
        trimStart,
        trimEnd,
        (progress) => setTrimProgress(Math.round(progress * 100))
      )
      const ext = trimmedBlob.type.includes('mp4') ? '.mp4' : '.webm'
      const baseName = file.name.replace(/\.[^.]+$/, '')
      const trimmedFile = new File([trimmedBlob], `${baseName}_6s${ext}`, { type: trimmedBlob.type })

      // Use known values instead of reading metadata from the trimmed blob.
      // WebM blobs from MediaRecorder often report Infinity for duration.
      onDone(trimmedFile, {
        duration: Math.round(trimEnd - trimStart),
        width: videoSize.width,
        height: videoSize.height
      })
    } catch (err) {
      console.error('Video trim failed:', err)
      alert('トリミングに失敗しました。元の動画で投稿します。')
      onDone(file, {
        duration: Math.round(clipDuration),
        width: videoSize.width,
        height: videoSize.height
      })
    } finally {
      trimmingRef.current = false
      setTrimming(false)
    }
  }

  // Format seconds as m:ss
  const formatTime = (s) => {
    if (!isFinite(s)) return '0:00'
    const mins = Math.floor(s / 60)
    const secs = Math.floor(s % 60)
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }

  const selectionLeft = duration > 0 ? (trimStart / duration) * 100 : 0
  const selectionWidth = duration > 0 ? (clipDuration / duration) * 100 : 100
  const playheadPos = duration > 0 ? (currentTime / duration) * 100 : 0

  return (
    <div className="fixed inset-0 bottom-16 lg:bottom-0 lg:left-[240px] xl:left-[280px] z-[60] bg-black flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 bg-black/80 flex-shrink-0">
        <button
          onClick={onCancel}
          className="text-white p-2"
          aria-label="戻る"
          disabled={trimming}
        >
          <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
        <span className="text-white font-semibold text-lg">動画を編集</span>
        <button
          onClick={handleDone}
          disabled={trimming}
          className="text-[var(--line-green)] font-semibold text-base px-3 py-1 disabled:opacity-50"
        >
          {trimming ? `${trimProgress}%` : '完了'}
        </button>
      </div>

      {/* Video Preview */}
      <div className="flex-1 flex items-center justify-center bg-black relative overflow-hidden min-h-0">
        {videoUrl && (
          <video
            key={videoUrl}
            ref={videoRef}
            src={videoUrl}
            crossOrigin="anonymous"
            className="max-w-full max-h-full object-contain"
            playsInline
            preload="auto"
            onLoadedData={handleLoadedData}
            onTimeUpdate={handleTimeUpdate}
            onClick={!trimming ? togglePlay : undefined}
          />
        )}
        {/* Play/Pause overlay */}
        {!playing && !trimming && (
          <button
            onClick={togglePlay}
            className="absolute inset-0 flex items-center justify-center bg-black/20"
            aria-label="再生"
          >
            <div className="w-16 h-16 rounded-full bg-black/50 flex items-center justify-center">
              <svg className="w-8 h-8 text-white ml-1" viewBox="0 0 24 24" fill="currentColor">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
            </div>
          </button>
        )}
        {/* Trimming progress overlay */}
        {trimming && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/60">
            <div className="w-48 h-1.5 bg-gray-700 rounded-full overflow-hidden">
              <div
                className="h-full bg-[var(--line-green)] rounded-full transition-all duration-200"
                style={{ width: `${trimProgress}%` }}
              />
            </div>
            <span className="text-white text-sm mt-3">トリミング中... {trimProgress}%</span>
          </div>
        )}
      </div>

      {/* Trim Controls */}
      <div className="bg-[#1a1a1a] px-4 py-4 flex-shrink-0 pb-safe">
        {/* Duration info */}
        <div className="flex items-center justify-between mb-3">
          <span className="text-gray-400 text-sm">
            {formatTime(trimStart)} - {formatTime(trimEnd)}
          </span>
          <span className="text-gray-400 text-sm">
            {duration <= MAX_CLIP_DURATION ? (
              `${formatTime(duration)} (トリミング不要)`
            ) : (
              `${MAX_CLIP_DURATION}秒 / ${formatTime(duration)}`
            )}
          </span>
        </div>

        {/* Trim Track */}
        <div
          ref={trackRef}
          className={`relative h-12 bg-gray-800 rounded-lg overflow-hidden select-none touch-none ${trimming ? 'opacity-50 pointer-events-none' : 'cursor-pointer'}`}
          onMouseDown={handleTrackInteraction}
          onTouchStart={handleTrackInteraction}
        >
          {/* Full track background */}
          <div className="absolute inset-0 bg-gray-700 rounded-lg" />

          {/* Selected region */}
          <div
            className="absolute top-0 bottom-0 border-2 border-[var(--line-green)] rounded-lg"
            style={{
              left: `${selectionLeft}%`,
              width: `${selectionWidth}%`,
              backgroundColor: 'rgba(76, 175, 80, 0.3)',
            }}
          >
            {/* Left handle */}
            <div className="absolute left-0 top-0 bottom-0 w-3 bg-[var(--line-green)] rounded-l-lg flex items-center justify-center cursor-ew-resize">
              <div className="w-0.5 h-4 bg-white/80 rounded" />
            </div>
            {/* Right handle */}
            <div className="absolute right-0 top-0 bottom-0 w-3 bg-[var(--line-green)] rounded-r-lg flex items-center justify-center cursor-ew-resize">
              <div className="w-0.5 h-4 bg-white/80 rounded" />
            </div>
          </div>

          {/* Playhead */}
          <div
            className="absolute top-0 bottom-0 w-0.5 bg-white z-10"
            style={{ left: `${playheadPos}%` }}
          />

          {/* Dimmed out-of-range areas */}
          <div
            className="absolute top-0 bottom-0 left-0 bg-black/60"
            style={{ width: `${selectionLeft}%` }}
          />
          <div
            className="absolute top-0 bottom-0 right-0 bg-black/60"
            style={{ width: `${100 - selectionLeft - selectionWidth}%` }}
          />
        </div>

        {/* 6-second label */}
        <div className="mt-2 text-center">
          <span className="text-gray-500 text-xs">
            diVine互換 · 最大{MAX_CLIP_DURATION}秒
          </span>
        </div>
      </div>
    </div>
  )
}

/**
 * Record a segment from an already-loaded video element using captureStream + MediaRecorder.
 * The video element must already be in the DOM and have its source loaded.
 */
function recordFromVideoElement(videoEl, startTime, endTime, onProgress) {
  return new Promise((resolve, reject) => {
    let settled = false
    const once = (fn) => (...args) => {
      if (settled) return
      settled = true
      clearTimeout(safetyTimer)
      fn(...args)
    }
    const resolveOnce = once(resolve)
    const rejectOnce = once(reject)

    const clipLen = endTime - startTime

    // Capture stream directly from the existing video element
    let stream
    try {
      stream = videoEl.captureStream()
    } catch {
      try {
        stream = videoEl.mozCaptureStream()
      } catch {
        rejectOnce(new Error('captureStream not supported'))
        return
      }
    }

    // Pick the best supported mime type
    const mimeType = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm', 'video/mp4']
      .find(m => MediaRecorder.isTypeSupported(m)) || ''

    let recorder
    try {
      recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined)
    } catch {
      try {
        recorder = new MediaRecorder(stream)
      } catch (e) {
        rejectOnce(e)
        return
      }
    }

    const chunks = []

    recorder.ondataavailable = (e) => {
      if (e.data.size > 0) chunks.push(e.data)
    }

    recorder.onstop = () => {
      videoEl.pause()
      stream.getTracks().forEach(t => t.stop())
      const blob = new Blob(chunks, { type: recorder.mimeType || 'video/webm' })
      onProgress?.(1)
      resolveOnce(blob)
    }

    recorder.onerror = (e) => {
      videoEl.pause()
      stream.getTracks().forEach(t => t.stop())
      rejectOnce(e.error || new Error('MediaRecorder error'))
    }

    // Monitor playback position for progress and stop
    const onTimeUpdate = () => {
      if (settled) return
      const elapsed = videoEl.currentTime - startTime
      onProgress?.(Math.min(elapsed / clipLen, 0.99))

      if (videoEl.currentTime >= endTime) {
        videoEl.removeEventListener('timeupdate', onTimeUpdate)
        if (recorder.state === 'recording') {
          recorder.stop()
        }
        videoEl.pause()
      }
    }
    videoEl.addEventListener('timeupdate', onTimeUpdate)

    // Handle video ending early
    const onEnded = () => {
      videoEl.removeEventListener('timeupdate', onTimeUpdate)
      videoEl.removeEventListener('ended', onEnded)
      if (recorder.state === 'recording') {
        recorder.stop()
      }
    }
    videoEl.addEventListener('ended', onEnded)

    // Safety timeout
    const safetyTimer = setTimeout(() => {
      videoEl.removeEventListener('timeupdate', onTimeUpdate)
      videoEl.removeEventListener('ended', onEnded)
      if (recorder.state === 'recording') {
        recorder.stop()
        videoEl.pause()
      }
    }, (clipLen + 5) * 1000)

    // Seek to start, then begin recording
    const startRecording = () => {
      recorder.start(100)
      videoEl.play().catch((err) => {
        videoEl.removeEventListener('timeupdate', onTimeUpdate)
        videoEl.removeEventListener('ended', onEnded)
        rejectOnce(err)
      })
    }

    // If already near startTime, start immediately; otherwise seek first
    if (Math.abs(videoEl.currentTime - startTime) < 0.05) {
      startRecording()
    } else {
      const onSeeked = () => {
        videoEl.removeEventListener('seeked', onSeeked)
        startRecording()
      }
      videoEl.addEventListener('seeked', onSeeked)
      videoEl.currentTime = startTime
    }
  })
}
