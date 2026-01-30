/**
 * Test utilities for React components and hooks
 */
import React, { ReactElement } from 'react'
import { render, RenderOptions } from '@testing-library/react'
import { act } from 'react'

/**
 * Custom render function with common providers
 */
interface CustomRenderOptions extends Omit<RenderOptions, 'wrapper'> {
  initialState?: Record<string, unknown>
}

function customRender(
  ui: ReactElement,
  options?: CustomRenderOptions
): ReturnType<typeof render> {
  const Wrapper = ({ children }: { children: React.ReactNode }) => {
    return <>{children}</>
  }

  return render(ui, { wrapper: Wrapper, ...options })
}

// Re-export everything from testing-library
export * from '@testing-library/react'
export { customRender as render }

/**
 * Wait for async updates
 */
export async function waitForAsync(ms: number = 0): Promise<void> {
  await act(async () => {
    await new Promise(resolve => setTimeout(resolve, ms))
  })
}

/**
 * Create a mock function with return value
 */
export function createMockFn<T>(returnValue?: T) {
  return vi.fn().mockReturnValue(returnValue)
}

/**
 * Create a mock async function with return value
 */
export function createMockAsyncFn<T>(returnValue?: T) {
  return vi.fn().mockResolvedValue(returnValue)
}

/**
 * Flush all pending promises
 */
export async function flushPromises(): Promise<void> {
  await act(async () => {
    await new Promise(process.nextTick)
  })
}

/**
 * Mock window properties for testing
 */
export function mockWindowProperty<T>(
  property: string,
  value: T
): () => void {
  const originalValue = (window as Record<string, unknown>)[property]
  Object.defineProperty(window, property, {
    value,
    writable: true,
    configurable: true,
  })
  return () => {
    Object.defineProperty(window, property, {
      value: originalValue,
      writable: true,
      configurable: true,
    })
  }
}
