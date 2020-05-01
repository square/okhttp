Caching
=======

OkHttp implements an optional, off by default, Cache. OkHttp aims for RFC correct and
pragmatic caching behaviour, following common real-world browser (Firefox, Chrome etc) and 
server behaviour when ambiguous.

# Basic Android Usage

```Kotlin tab=
  private val client: OkHttpClient = OkHttpClient.Builder()
      .cache(Cache(
          directory = cacheDirectory,
          maxSize = 10L * 1024L * 1024L // 10 MiB
      ))
      .build()
```

### TODO

 - HTTP caching heuristics source?
 - what it means if cacheResponse() and networkResponse() are both non-null (issue #4539)
 - why you need to read the entire response before an entry is stored
 - the cache must not compete with other processes for the same directory
 - pruning the cache manually
 - cache with offline only (accept stale) or offline preferred behaviour
