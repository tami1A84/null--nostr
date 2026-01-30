/**
 * Type declarations for optional Capacitor modules
 *
 * These declarations allow the code to compile even when the
 * Capacitor packages are not installed (e.g., in web-only builds).
 */

declare module '@capacitor/preferences' {
  export interface GetResult {
    value: string | null
  }

  export interface KeysResult {
    keys: string[]
  }

  export const Preferences: {
    get(options: { key: string }): Promise<GetResult>
    set(options: { key: string; value: string }): Promise<void>
    remove(options: { key: string }): Promise<void>
    clear(): Promise<void>
    keys(): Promise<KeysResult>
  }
}
