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

## EventListener events 

Cache Events are exposed via the EventListener API.  Typical scenarios are

### Cache Hit

In the ideal scenario, the cache can fulfill the request without any conditional call to the network.
This will skip the normal events such as DNS, connecting to the network, and reading the response body.

 - CallStart
 - **CacheHit**
 - CallEnd
 
### Cache Miss

Under a cache miss, the normal request events are seen, but an additional event shows the presence of the cache.

 - CallStart 
 - **CacheMiss**
 - ProxySelectStart
 - ... Standard Events ...
 - CallEnd
        
### Conditional Cache Hit
 
When cache flags, require checking the cache results are still valid, an early cacheConditionalHit event is
received, followed by a cache hit or miss.  Critically in the cache hit scenario, the response body will be 0 bytes.

The response will have non-null cacheResponse and networkResponse. The cacheResponse will be used as the top level
response only if the response code is HTTP_NOT_MODIFIED (304).
 
 - CallStart
 - **CacheConditionalHit**
 - ConnectionAcquired
 - ... Standard Events...
 - ResponseBodyEnd _(0 bytes)_
 - **CacheHit**
 - ConnectionReleased
 - CallEnd
 
## Cache directory

The cache directory must be exclusively owned by a single instance.

Deleting the cache when it is no longer needed can be done.  However this may delete the purpose of the cache
which is designed to persist between app restarts.

```
cache.delete()
```
 
## Pruning the Cache

Pruning the entire Cache to clear space temporarily, can be done using evictAll.

```
cache.evictAll()
```

Removing individual items can be done using the urls iterator.

```
    val urlIterator = cache.urls()
    while (urlIterator.hasNext()) {
      if (urlIterator.next().startsWith("https://www.google.com/")) {
        urlIterator.remove()
      }
    }
```

### TODO

 - HTTP caching heuristics source?
 - why you need to read the entire response before an entry is stored
 - cache with offline only (accept stale) or offline preferred behaviour
