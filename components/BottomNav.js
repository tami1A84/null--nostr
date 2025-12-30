'use client'

const tabs = [
  {
    id: 'home',
    label: 'ホーム',
    icon: (active) => (
      <svg className="w-6 h-6" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={active ? 0 : 1.8}>
        {active ? (
          <path d="M12 2L3 9v12a1 1 0 001 1h5a1 1 0 001-1v-5a1 1 0 011-1h2a1 1 0 011 1v5a1 1 0 001 1h5a1 1 0 001-1V9l-9-7z"/>
        ) : (
          <path strokeLinecap="round" strokeLinejoin="round" d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2V9z M9 22V12h6v10"/>
        )}
      </svg>
    )
  },
  {
    id: 'talk',
    label: 'トーク',
    icon: (active) => (
      <svg className="w-6 h-6" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={active ? 0 : 1.8}>
        {active ? (
          <path d="M12 2C6.48 2 2 5.58 2 10c0 2.62 1.34 4.98 3.5 6.56V21l4.22-2.33c.73.18 1.49.33 2.28.33 5.52 0 10-3.58 10-8S17.52 2 12 2z"/>
        ) : (
          <path strokeLinecap="round" strokeLinejoin="round" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.42-4.03 8-9 8-1.5 0-2.92-.32-4.19-.88L3 21l1.9-3.8C3.71 15.77 3 14.01 3 12c0-4.42 4.03-8 9-8s9 3.58 9 8z"/>
        )}
      </svg>
    )
  },
  {
    id: 'timeline',
    label: 'タイムライン',
    icon: (active) => (
      <svg className="w-6 h-6" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={active ? 0 : 1.8}>
        {active ? (
          <path d="M4 4h16a2 2 0 012 2v12a2 2 0 01-2 2H4a2 2 0 01-2-2V6a2 2 0 012-2zm2 4v2h4V8H6zm0 4v2h8v-2H6zm0 4v2h6v-2H6zm10-8v10h2V8h-2z"/>
        ) : (
          <path strokeLinecap="round" strokeLinejoin="round" d="M19 20H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v12a2 2 0 01-2 2zM16 2v4M8 2v4M3 10h18"/>
        )}
      </svg>
    )
  },
  {
    id: 'miniapp',
    label: 'ミニアプリ',
    icon: (active) => (
      <svg className="w-6 h-6" viewBox="0 0 24 24" fill={active ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={active ? 0 : 1.8}>
        {active ? (
          <path d="M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm10 0h6v6h-6v-6z"/>
        ) : (
          <path strokeLinecap="round" strokeLinejoin="round" d="M4 4h6v6H4V4zm10 0h6v6h-6V4zM4 14h6v6H4v-6zm10 0h6v6h-6v-6z"/>
        )}
      </svg>
    )
  }
]

export default function BottomNav({ activeTab, onTabChange }) {
  return (
    <nav
      className="fixed bottom-0 left-0 right-0 z-50 bottom-nav pb-safe"
      role="tablist"
      aria-label="メインナビゲーション"
    >
      <div className="flex items-center justify-around h-14">
        {tabs.map((tab) => {
          const isActive = activeTab === tab.id
          return (
            <button
              key={tab.id}
              onClick={() => onTabChange(tab.id)}
              className={`flex flex-col items-center justify-center flex-1 h-full transition-colors duration-200 action-btn ${
                isActive ? 'tab-active' : 'tab-inactive'
              }`}
              role="tab"
              aria-selected={isActive}
              aria-label={`${tab.label}タブ`}
              aria-controls={`${tab.id}-panel`}
            >
              <div
                className={`transition-transform duration-200 ${isActive ? 'scale-105' : ''}`}
                aria-hidden="true"
              >
                {tab.icon(isActive)}
              </div>
              <span className="text-[10px] mt-0.5 font-medium">
                {tab.label}
              </span>
            </button>
          )
        })}
      </div>
    </nav>
  )
}
