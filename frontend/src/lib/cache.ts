interface CacheEntry<T> {
  data: T
  expiresAt: number
}

export function createCache<T>(ttlMs: number) {
  const store = new Map<string, CacheEntry<T>>()

  return {
    async get(key: string, fetcher: () => Promise<T>): Promise<T> {
      const hit = store.get(key)
      if (hit && hit.expiresAt > Date.now()) return hit.data
      const data = await fetcher()
      store.set(key, { data, expiresAt: Date.now() + ttlMs })
      return data
    },
    clear(): void {
      store.clear()
    },
  }
}
