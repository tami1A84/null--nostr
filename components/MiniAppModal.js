'use client'

import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'

export default function MiniAppModal({ isOpen, onClose, title, children }) {
  const [mounted, setMounted] = useState(false)
  const modalRef = useRef(null)

  useEffect(() => {
    setMounted(true)
    return () => setMounted(false)
  }, [])

  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [isOpen])

  if (!mounted || !isOpen) return null

  const modalContent = (
    <>
      {/* Overlay for desktop */}
      <div
        className="fixed inset-0 z-[90] bg-black/40 backdrop-blur-sm lg:block hidden"
        onClick={onClose}
      />

      <div className="fixed inset-0 lg:inset-auto lg:top-1/2 lg:left-1/2 lg:-translate-x-1/2 lg:-translate-y-1/2
                      z-[100] flex flex-col bg-[#0a0a0a]
                      lg:w-full lg:max-w-3xl lg:h-[90vh] lg:max-h-[800px] lg:rounded-2xl lg:shadow-2xl lg:border lg:border-[var(--border-color)]">
        {/* Header */}
        <header className="sticky top-0 z-10 header-blur border-b border-[var(--border-color)] flex-shrink-0 lg:rounded-t-2xl">
          <div className="flex items-center justify-between px-4 h-14">
            <button onClick={onClose} className="p-2 -ml-2 text-[var(--text-primary)] hover:opacity-70 transition-opacity">
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
            <h2 className="font-bold text-[var(--text-primary)] truncate px-4">{title}</h2>
            <div className="w-10" /> {/* Spacer */}
          </div>
        </header>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-4 lg:p-6">
          <div className="max-w-2xl mx-auto">
            {children}
          </div>
        </div>
      </div>
    </>
  )

  return createPortal(modalContent, document.body)
}
