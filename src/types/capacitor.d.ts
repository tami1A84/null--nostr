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

declare module '@capacitor/clipboard' {
  export interface ClipboardWriteOptions {
    string?: string
    image?: string
    url?: string
    label?: string
  }

  export interface ClipboardReadResult {
    type: string
    value: string
  }

  export const Clipboard: {
    write(options: ClipboardWriteOptions): Promise<void>
    read(): Promise<ClipboardReadResult>
  }
}

declare module '@capacitor/network' {
  export interface ConnectionStatus {
    connected: boolean
    connectionType: 'wifi' | 'cellular' | 'none' | 'unknown'
  }

  export interface PluginListenerHandle {
    remove(): Promise<void>
  }

  export const Network: {
    getStatus(): Promise<ConnectionStatus>
    addListener(
      eventName: 'networkStatusChange',
      listener: (status: ConnectionStatus) => void
    ): Promise<PluginListenerHandle>
  }
}
