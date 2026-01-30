'use client'

import React from 'react'
import type { TimelineMode } from '../../types'

interface TimelineHeaderProps {
  timelineMode: TimelineMode
  onModeChange: (mode: TimelineMode) => void
  onSearchOpen: () => void
  loading?: boolean
  onRefresh?: () => void
  className?: string
}

/**
 * Timeline header with mode tabs and search
 * Supports both mobile (tabs) and desktop (title) layouts
 */
export function TimelineHeader({
  timelineMode,
  onModeChange,
  onSearchOpen,
  loading,
  onRefresh,
  className
}: TimelineHeaderProps) {
  return (
    <header className={`fixed top-0 left-0 right-0 lg:left-[240px] xl:left-[280px] z-30 header-blur border-b border-[var(--border-color)] ${className || ''}`}>
      <div className="flex items-center justify-between px-4 h-14 lg:h-16">
        {/* Tab Switcher (Mobile only) */}
        <div className="flex items-center gap-1 lg:hidden">
          <TabButton
            active={timelineMode === 'global'}
            onClick={() => onModeChange('global')}
          >
            おすすめ
          </TabButton>
          <TabButton
            active={timelineMode === 'following'}
            onClick={() => onModeChange('following')}
          >
            フォロー
          </TabButton>
        </div>

        {/* Desktop Title */}
        <h1 className="hidden lg:block text-lg font-bold text-[var(--text-primary)]">
          タイムライン
        </h1>

        {/* Desktop Search Bar */}
        <div className="hidden lg:flex flex-1 max-w-md mx-8">
          <button
            onClick={onSearchOpen}
            className="w-full flex items-center gap-3 px-5 py-2.5 rounded-full bg-[var(--bg-secondary)] border border-[var(--border-color)] text-[var(--text-tertiary)] hover:border-[var(--text-tertiary)] transition-all"
          >
            <SearchIcon className="w-5 h-5" />
            <span className="text-sm">検索...</span>
          </button>
        </div>

        {/* Mobile Search Button */}
        <button
          onClick={onSearchOpen}
          className="lg:hidden text-[var(--text-secondary)] action-btn p-2"
        >
          <SearchIcon className="w-5 h-5" />
        </button>

        {/* Spacer for desktop */}
        <div className="hidden lg:block w-20" />
      </div>
    </header>
  )
}

interface TabButtonProps {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}

function TabButton({ active, onClick, children }: TabButtonProps) {
  return (
    <button
      onClick={onClick}
      className={`px-3 py-1.5 text-sm rounded-full transition-all ${
        active
          ? 'bg-[var(--line-green)] text-white font-medium shadow-sm'
          : 'text-[var(--text-tertiary)] hover:bg-[var(--bg-tertiary)]'
      }`}
    >
      {children}
    </button>
  )
}

function SearchIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="11" cy="11" r="8" />
      <line x1="21" y1="21" x2="16.65" y2="16.65" />
    </svg>
  )
}

interface ColumnHeaderProps {
  title: string
  onRefresh?: () => void
  loading?: boolean
  className?: string
}

/**
 * Header for desktop column (Recommend/Following)
 */
export function ColumnHeader({ title, onRefresh, loading, className }: ColumnHeaderProps) {
  return (
    <div className={`flex-shrink-0 bg-[var(--bg-primary)] border-b border-[var(--border-color)] px-4 py-3 flex items-center justify-between ${className || ''}`}>
      <h2 className="font-bold text-[var(--text-primary)]">{title}</h2>
      {onRefresh && (
        <button
          onClick={onRefresh}
          disabled={loading}
          className="p-2 rounded-full text-[var(--text-tertiary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)] transition-all disabled:opacity-50"
          title={`${title}を更新`}
        >
          <svg
            className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <path d="M23 4v6h-6M1 20v-6h6" />
            <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15" />
          </svg>
        </button>
      )}
    </div>
  )
}

export default TimelineHeader
