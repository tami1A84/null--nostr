'use client'

import { useState, useRef, useEffect, useCallback } from 'react'

const MAX_CLIP_DURATION = 6 // seconds

/**
 * Video editor for trimming videos to 6-second clips (diVine-compatible format)
 *
 * Props:
 *   file: File - the original video file
 *   onDone: (file: File, meta: { duration, width, height, trimStart, trimEnd }) => void
 *   onCancel: () => void
 */
export default function VideoEditor({ file, onDone, onCancel }) {
  const videoRef = useRef(null)
  const trackRef = useRef(null)
  const animFrameRef = useRef(null)

  const [duration, setDuration] = useState(0)
  const [trimStart, setTrimStart] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [videoUrl, setVideoUrl] = useState(null)
  const [videoSize, setVideoSize] = useState({ width: 0, height: 0 })
  const [currentTime, setCurrentTime] = useState(0)

  // Clip end time
  const clipDuration = Math.min(MAX_CLIP_DURATION, duration)
  const trimEnd = Math.min(trimStart + clipDuration, duration)

  // Create object URL
  useEffect(() => {
    const url = URL.createObjectURL(file)
    setVideoUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [file])

  // When metadata loaded
  const handleLoadedMetadata = () => {
    const video = videoRef.current
    if (!video) return
    setDuration(video.duration)
    setVideoSize({ width: video.videoWidth, height: video.videoHeight })
    video.currentTime = 0
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

  // Handle time update - loop within trim range
  const handleTimeUpdate = () => {
    const video = videoRef.current
    if (!video) return
    setCurrentTime(video.currentTime)
    if (video.currentTime >= trimEnd) {
      video.currentTime = trimStart
    }
  }

  // Drag trim handle
  const handleTrackInteraction = (e) => {
    const track = trackRef.current
    if (!track || duration <= 0) return

    e.preventDefault()
    const updateFromEvent = (evt) => {
      const rect = track.getBoundingClientRect()
      const clientX = evt.touches ? evt.touches[0].clientX : evt.clientX
      const ratio = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width))
      const time = ratio * duration
      // Center the clip on tap position
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

  // Done - pass original file with trim metadata (no slow re-encoding)
  const handleDone = () => {
    if (!videoRef.current) return

    const needsTrim = duration > MAX_CLIP_DURATION

    onDone(file, {
      duration: Math.round(needsTrim ? clipDuration : duration),
      width: videoSize.width,
      height: videoSize.height,
      trimStart: needsTrim ? trimStart : 0,
      trimEnd: needsTrim ? trimEnd : duration
    })
  }

  // Format seconds as m:ss
  const formatTime = (s) => {
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
        >
          <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
        <span className="text-white font-semibold text-lg">動画を編集</span>
        <button
          onClick={handleDone}
          className="text-[var(--line-green)] font-semibold text-base px-3 py-1"
        >
          完了
        </button>
      </div>

      {/* Video Preview */}
      <div className="flex-1 flex items-center justify-center bg-black relative overflow-hidden min-h-0">
        {videoUrl && (
          <video
            ref={videoRef}
            src={videoUrl}
            className="max-w-full max-h-full object-contain"
            playsInline
            muted={false}
            onLoadedMetadata={handleLoadedMetadata}
            onTimeUpdate={handleTimeUpdate}
            onClick={togglePlay}
          />
        )}
        {/* Play/Pause overlay */}
        {!playing && (
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
          className="relative h-12 bg-gray-800 rounded-lg overflow-hidden cursor-pointer select-none touch-none"
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
