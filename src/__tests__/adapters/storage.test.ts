/**
 * Storage Adapter Unit Tests
 *
 * Tests for MemoryStorage implementation which is used for testing
 * and as a reference implementation for other storage adapters.
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { MemoryStorage, createMemoryStorage } from '@/src/adapters/storage/MemoryStorage'

describe('MemoryStorage', () => {
  let storage: MemoryStorage

  beforeEach(() => {
    storage = new MemoryStorage()
  })

  describe('basic operations', () => {
    it('should return null for non-existent key', async () => {
      const result = await storage.getItem('nonexistent')
      expect(result).toBeNull()
    })

    it('should set and get a value', async () => {
      await storage.setItem('key1', 'value1')
      const result = await storage.getItem('key1')
      expect(result).toBe('value1')
    })

    it('should overwrite existing value', async () => {
      await storage.setItem('key1', 'value1')
      await storage.setItem('key1', 'value2')
      const result = await storage.getItem('key1')
      expect(result).toBe('value2')
    })

    it('should remove an item', async () => {
      await storage.setItem('key1', 'value1')
      await storage.removeItem('key1')
      const result = await storage.getItem('key1')
      expect(result).toBeNull()
    })

    it('should not throw when removing non-existent key', async () => {
      await expect(storage.removeItem('nonexistent')).resolves.not.toThrow()
    })

    it('should clear all items', async () => {
      await storage.setItem('key1', 'value1')
      await storage.setItem('key2', 'value2')
      await storage.clear()

      expect(await storage.getItem('key1')).toBeNull()
      expect(await storage.getItem('key2')).toBeNull()
      expect(storage.size).toBe(0)
    })
  })

  describe('keys', () => {
    it('should return empty array for empty storage', async () => {
      const keys = await storage.keys()
      expect(keys).toEqual([])
    })

    it('should return all keys', async () => {
      await storage.setItem('key1', 'value1')
      await storage.setItem('key2', 'value2')
      await storage.setItem('key3', 'value3')

      const keys = await storage.keys()
      expect(keys).toHaveLength(3)
      expect(keys).toContain('key1')
      expect(keys).toContain('key2')
      expect(keys).toContain('key3')
    })
  })

  describe('has', () => {
    it('should return false for non-existent key', async () => {
      const result = await storage.has('nonexistent')
      expect(result).toBe(false)
    })

    it('should return true for existing key', async () => {
      await storage.setItem('key1', 'value1')
      const result = await storage.has('key1')
      expect(result).toBe(true)
    })
  })

  describe('getMultiple', () => {
    it('should return null for all non-existent keys', async () => {
      const result = await storage.getMultiple(['key1', 'key2'])
      expect(result.get('key1')).toBeNull()
      expect(result.get('key2')).toBeNull()
    })

    it('should return values for existing keys', async () => {
      await storage.setItem('key1', 'value1')
      await storage.setItem('key2', 'value2')

      const result = await storage.getMultiple(['key1', 'key2', 'key3'])
      expect(result.get('key1')).toBe('value1')
      expect(result.get('key2')).toBe('value2')
      expect(result.get('key3')).toBeNull()
    })
  })

  describe('setMultiple', () => {
    it('should set multiple values at once', async () => {
      await storage.setMultiple([
        ['key1', 'value1'],
        ['key2', 'value2'],
      ])

      expect(await storage.getItem('key1')).toBe('value1')
      expect(await storage.getItem('key2')).toBe('value2')
    })

    it('should overwrite existing values', async () => {
      await storage.setItem('key1', 'oldValue')
      await storage.setMultiple([
        ['key1', 'newValue'],
        ['key2', 'value2'],
      ])

      expect(await storage.getItem('key1')).toBe('newValue')
    })
  })

  describe('JSON operations', () => {
    it('should set and get JSON object', async () => {
      const data = { name: 'test', value: 123 }
      await storage.setJSON('json1', data)

      const result = await storage.getJSON<typeof data>('json1')
      expect(result).toEqual(data)
    })

    it('should handle arrays', async () => {
      const data = [1, 2, 3, 'test']
      await storage.setJSON('arr1', data)

      const result = await storage.getJSON<typeof data>('arr1')
      expect(result).toEqual(data)
    })

    it('should handle nested objects', async () => {
      const data = {
        user: {
          name: 'test',
          settings: {
            theme: 'dark',
            notifications: true,
          },
        },
      }
      await storage.setJSON('nested', data)

      const result = await storage.getJSON<typeof data>('nested')
      expect(result).toEqual(data)
    })

    it('should return null for non-existent JSON key', async () => {
      const result = await storage.getJSON('nonexistent')
      expect(result).toBeNull()
    })

    it('should return null for invalid JSON', async () => {
      await storage.setItem('invalid', 'not valid json{')
      const result = await storage.getJSON('invalid')
      expect(result).toBeNull()
    })

    it('should handle primitive values', async () => {
      await storage.setJSON('number', 42)
      await storage.setJSON('string', 'hello')
      await storage.setJSON('boolean', true)
      await storage.setJSON('null', null)

      expect(await storage.getJSON('number')).toBe(42)
      expect(await storage.getJSON('string')).toBe('hello')
      expect(await storage.getJSON('boolean')).toBe(true)
      expect(await storage.getJSON('null')).toBeNull()
    })
  })

  describe('size', () => {
    it('should return 0 for empty storage', () => {
      expect(storage.size).toBe(0)
    })

    it('should return correct count', async () => {
      await storage.setItem('key1', 'value1')
      expect(storage.size).toBe(1)

      await storage.setItem('key2', 'value2')
      expect(storage.size).toBe(2)

      await storage.removeItem('key1')
      expect(storage.size).toBe(1)
    })
  })

  describe('toObject / fromObject', () => {
    it('should export data as object', async () => {
      await storage.setItem('key1', 'value1')
      await storage.setItem('key2', 'value2')

      const obj = storage.toObject()
      expect(obj).toEqual({
        key1: 'value1',
        key2: 'value2',
      })
    })

    it('should import data from object', async () => {
      storage.fromObject({
        key1: 'value1',
        key2: 'value2',
      })

      expect(await storage.getItem('key1')).toBe('value1')
      expect(await storage.getItem('key2')).toBe('value2')
    })

    it('should clear existing data when importing', async () => {
      await storage.setItem('existing', 'value')
      storage.fromObject({
        new: 'data',
      })

      expect(await storage.getItem('existing')).toBeNull()
      expect(await storage.getItem('new')).toBe('data')
    })
  })

  describe('constructor with initial data', () => {
    it('should accept initial data as object', async () => {
      const storage = new MemoryStorage({ key1: 'value1', key2: 'value2' })

      expect(await storage.getItem('key1')).toBe('value1')
      expect(await storage.getItem('key2')).toBe('value2')
    })

    it('should accept initial data as Map', async () => {
      const initialData = new Map([
        ['key1', 'value1'],
        ['key2', 'value2'],
      ])
      const storage = new MemoryStorage(initialData)

      expect(await storage.getItem('key1')).toBe('value1')
      expect(await storage.getItem('key2')).toBe('value2')
    })
  })

  describe('createMemoryStorage factory', () => {
    it('should create new instance', () => {
      const storage1 = createMemoryStorage()
      const storage2 = createMemoryStorage()

      expect(storage1).toBeInstanceOf(MemoryStorage)
      expect(storage2).toBeInstanceOf(MemoryStorage)
      expect(storage1).not.toBe(storage2)
    })

    it('should create instance with initial data', async () => {
      const storage = createMemoryStorage({ key1: 'value1' })
      expect(await storage.getItem('key1')).toBe('value1')
    })
  })
})
