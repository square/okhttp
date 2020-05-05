Caching
=======

OkHttp implements an optional, off by default, Cache. OkHttp aims for RFC correct and
pragmatic caching behaviour, following common real-world browser like Firefox/Chrome and 
server behaviour when ambiguous.

# Basic Android Usage

```Kotlin tab=
  private val client: OkHttpClient = OkHttpClient.Builder()
      .cache(Cache(
          directory = File(application.cacheDir, "http_cache"),
          maxSize = 10L * 1024L * 1024L // 10 MiB
      ))
      .build()
```

### Event Listeners

Cache Events are exposed via the EventListener API.  Typical scenarios are

 Cache Hit
 - CallStart
 - _CacheHit_
 - CallEnd
 
Cache Miss
 - CallStart 
 - _CacheMiss_
 - ProxySelectStart
 - ... Standard Events ...
 - CallEnd
        
 Conditional Cache Hit
 
 - CallStart
 - _CacheConditionalHit_
 - ConnectionAcquired
 - ... Standard Events...
 - ResponseBodyEnd _(0 bytes)_
 - _CacheHit_
 - ConnectionReleased
 - CallEnd

### TODO

 - HTTP caching heuristics source?
 - what it means if cacheResponse() and networkResponse() are both non-null (issue #4539)
 - why you need to read the entire response before an entry is stored
 - the cache must not compete with other processes for the same directory
 - pruning the cache manually
 - cache with offline only (accept stale) or offline preferred behaviour
