/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.cache

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.Flushable
import java.io.IOException
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.NoSuchElementException
import okhttp3.internal.assertThreadHoldsLock
import okhttp3.internal.cache.DiskLruCache.Editor
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.io.FileSystem
import okhttp3.internal.okHttpName
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.WARN
import okio.BufferedSink
import okio.Sink
import okio.Source
import okio.blackholeSink
import okio.buffer

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache entry has a string key
 * and a fixed number of values. Each key must match the regex `[a-z0-9_-]{1,64}`. Values are byte
 * sequences, accessible as streams or files. Each value must be between `0` and `Int.MAX_VALUE`
 * bytes in length.
 *
 * The cache stores its data in a directory on the filesystem. This directory must be exclusive to
 * the cache; the cache may delete or overwrite files from its directory. It is an error for
 * multiple processes to use the same cache directory at the same time.
 *
 * This cache limits the number of bytes that it will store on the filesystem. When the number of
 * stored bytes exceeds the limit, the cache will remove entries in the background until the limit
 * is satisfied. The limit is not strict: the cache may temporarily exceed it while waiting for
 * files to be deleted. The limit does not include filesystem overhead or the cache journal so
 * space-sensitive applications should set a conservative limit.
 *
 * Clients call [edit] to create or update the values of an entry. An entry may have only one editor
 * at one time; if a value is not available to be edited then [edit] will return null.
 *
 *  * When an entry is being **created** it is necessary to supply a full set of values; the empty
 *    value should be used as a placeholder if necessary.
 *
 *  * When an entry is being **edited**, it is not necessary to supply data for every value; values
 *    default to their previous value.
 *
 * Every [edit] call must be matched by a call to [Editor.commit] or [Editor.abort]. Committing is
 * atomic: a read observes the full set of values as they were before or after the commit, but never
 * a mix of values.
 *
 * Clients call [get] to read a snapshot of an entry. The read will observe the value at the time
 * that [get] was called. Updates and removals after the call do not impact ongoing reads.
 *
 * This class is tolerant of some I/O errors. If files are missing from the filesystem, the
 * corresponding entries will be dropped from the cache. If an error occurs while writing a cache
 * value, the edit will fail silently. Callers should handle other problems by catching
 * `IOException` and responding appropriately.
 *
 * @constructor Create a cache which will reside in [directory]. This cache is lazily initialized on
 *     first access and will be created if it does not exist.
 * @param directory a writable directory.
 * @param valueCount the number of values per cache entry. Must be positive.
 * @param maxSize the maximum number of bytes this cache should use to store.
 */
class DiskLruCache internal constructor(
  internal val fileSystem: FileSystem,

  /** Returns the directory where this cache stores its data. */
  val directory: File,

  private val appVersion: Int,

  internal val valueCount: Int,

  /** Returns the maximum number of bytes that this cache should use to store its data. */
  maxSize: Long,

  /** Used for asynchronous journal rebuilds. */
  taskRunner: TaskRunner
) : Closeable, Flushable {
  /** The maximum number of bytes that this cache should use to store its data. */
  @get:Synchronized @set:Synchronized var maxSize: Long = maxSize
    set(value) {
      field = value
      if (initialized) {
        cleanupQueue.schedule(cleanupTask) // Trim the existing store if necessary.
      }
    }

  /*
   * This cache uses a journal file named "journal". A typical journal file looks like this:
   *
   *     libcore.io.DiskLruCache
   *     1
   *     100
   *     2
   *
   *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
   *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
   *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
   *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
   *     DIRTY 1ab96a171faeeee38496d8b330771a7a
   *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
   *     READ 335c4c6028171cfddfbaae1a9c313c52
   *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
   *
   * The first five lines of the journal form its header. They are the constant string
   * "libcore.io.DiskLruCache", the disk cache's version, the application's version, the value
   * count, and a blank line.
   *
   * Each of the subsequent lines in the file is a record of the state of a cache entry. Each line
   * contains space-separated values: a state, a key, and optional state-specific values.
   *
   *   o DIRTY lines track that an entry is actively being created or updated. Every successful
   *     DIRTY action should be followed by a CLEAN or REMOVE action. DIRTY lines without a matching
   *     CLEAN or REMOVE indicate that temporary files may need to be deleted.
   *
   *   o CLEAN lines track a cache entry that has been successfully published and may be read. A
   *     publish line is followed by the lengths of each of its values.
   *
   *   o READ lines track accesses for LRU.
   *
   *   o REMOVE lines track entries that have been deleted.
   *
   * The journal file is appended to as cache operations occur. The journal may occasionally be
   * compacted by dropping redundant lines. A temporary file named "journal.tmp" will be used during
   * compaction; that file should be deleted if it exists when the cache is opened.
   */

  private val journalFile: File
  private val journalFileTmp: File
  private val journalFileBackup: File
  private var size: Long = 0L
  private var journalWriter: BufferedSink? = null
  internal val lruEntries = LinkedHashMap<String, Entry>(0, 0.75f, true)
  private var redundantOpCount: Int = 0
  private var hasJournalErrors: Boolean = false

  // Must be read and written when synchronized on 'this'.
  private var initialized: Boolean = false
  internal var closed: Boolean = false
  private var mostRecentTrimFailed: Boolean = false
  private var mostRecentRebuildFailed: Boolean = false

  /**
   * To differentiate between old and current snapshots, each entry is given a sequence number each
   * time an edit is committed. A snapshot is stale if its sequence number is not equal to its
   * entry's sequence number.
   */
  private var nextSequenceNumber: Long = 0

  private val cleanupQueue = taskRunner.newQueue()
  private val cleanupTask = object : Task("$okHttpName Cache") {
    override fun runOnce(): Long {
      synchronized(this@DiskLruCache) {
        if (!initialized || closed) {
          return -1L // Nothing to do.
        }

        try {
          trimToSize()
        } catch (_: IOException) {
          mostRecentTrimFailed = true
        }

        try {
          if (journalRebuildRequired()) {
            rebuildJournal()
            redundantOpCount = 0
          }
        } catch (_: IOException) {
          mostRecentRebuildFailed = true
          journalWriter = blackholeSink().buffer()
        }

        return -1L
      }
    }
  }

  init {
    require(maxSize > 0L) { "maxSize <= 0" }
    require(valueCount > 0) { "valueCount <= 0" }

    this.journalFile = File(directory, JOURNAL_FILE)
    this.journalFileTmp = File(directory, JOURNAL_FILE_TEMP)
    this.journalFileBackup = File(directory, JOURNAL_FILE_BACKUP)
  }

  @Synchronized @Throws(IOException::class)
  fun initialize() {
    this.assertThreadHoldsLock()

    if (initialized) {
      return // Already initialized.
    }

    // If a bkp file exists, use it instead.
    if (fileSystem.exists(journalFileBackup)) {
      // If journal file also exists just delete backup file.
      if (fileSystem.exists(journalFile)) {
        fileSystem.delete(journalFileBackup)
      } else {
        fileSystem.rename(journalFileBackup, journalFile)
      }
    }

    // Prefer to pick up where we left off.
    if (fileSystem.exists(journalFile)) {
      try {
        readJournal()
        processJournal()
        initialized = true
        return
      } catch (journalIsCorrupt: IOException) {
        Platform.get().log(
            "DiskLruCache $directory is corrupt: ${journalIsCorrupt.message}, removing",
            WARN,
            journalIsCorrupt)
      }

      // The cache is corrupted, attempt to delete the contents of the directory. This can throw and
      // we'll let that propagate out as it likely means there is a severe filesystem problem.
      try {
        delete()
      } finally {
        closed = false
      }
    }

    rebuildJournal()

    initialized = true
  }

  @Throws(IOException::class)
  private fun readJournal() {
    fileSystem.source(journalFile).buffer().use { source ->
      val magic = source.readUtf8LineStrict()
      val version = source.readUtf8LineStrict()
      val appVersionString = source.readUtf8LineStrict()
      val valueCountString = source.readUtf8LineStrict()
      val blank = source.readUtf8LineStrict()

      if (MAGIC != magic ||
          VERSION_1 != version ||
          appVersion.toString() != appVersionString ||
          valueCount.toString() != valueCountString ||
          blank.isNotEmpty()) {
        throw IOException(
            "unexpected journal header: [$magic, $version, $valueCountString, $blank]")
      }

      var lineCount = 0
      while (true) {
        try {
          readJournalLine(source.readUtf8LineStrict())
          lineCount++
        } catch (_: EOFException) {
          break // End of journal.
        }
      }

      redundantOpCount = lineCount - lruEntries.size

      // If we ended on a truncated line, rebuild the journal before appending to it.
      if (!source.exhausted()) {
        rebuildJournal()
      } else {
        journalWriter = newJournalWriter()
      }
    }
  }

  @Throws(FileNotFoundException::class)
  private fun newJournalWriter(): BufferedSink {
    val fileSink = fileSystem.appendingSink(journalFile)
    val faultHidingSink = FaultHidingSink(fileSink) {
      this@DiskLruCache.assertThreadHoldsLock()
      hasJournalErrors = true
    }
    return faultHidingSink.buffer()
  }

  @Throws(IOException::class)
  private fun readJournalLine(line: String) {
    val firstSpace = line.indexOf(' ')
    if (firstSpace == -1) throw IOException("unexpected journal line: $line")

    val keyBegin = firstSpace + 1
    val secondSpace = line.indexOf(' ', keyBegin)
    val key: String
    if (secondSpace == -1) {
      key = line.substring(keyBegin)
      if (firstSpace == REMOVE.length && line.startsWith(REMOVE)) {
        lruEntries.remove(key)
        return
      }
    } else {
      key = line.substring(keyBegin, secondSpace)
    }

    var entry: Entry? = lruEntries[key]
    if (entry == null) {
      entry = Entry(key)
      lruEntries[key] = entry
    }

    when {
      secondSpace != -1 && firstSpace == CLEAN.length && line.startsWith(CLEAN) -> {
        val parts = line.substring(secondSpace + 1)
            .split(' ')
        entry.readable = true
        entry.currentEditor = null
        entry.setLengths(parts)
      }

      secondSpace == -1 && firstSpace == DIRTY.length && line.startsWith(DIRTY) -> {
        entry.currentEditor = Editor(entry)
      }

      secondSpace == -1 && firstSpace == READ.length && line.startsWith(READ) -> {
        // This work was already done by calling lruEntries.get().
      }

      else -> throw IOException("unexpected journal line: $line")
    }
  }

  /**
   * Computes the initial size and collects garbage as a part of opening the cache. Dirty entries
   * are assumed to be inconsistent and will be deleted.
   */
  @Throws(IOException::class)
  private fun processJournal() {
    fileSystem.delete(journalFileTmp)
    val i = lruEntries.values.iterator()
    while (i.hasNext()) {
      val entry = i.next()
      if (entry.currentEditor == null) {
        for (t in 0 until valueCount) {
          size += entry.lengths[t]
        }
      } else {
        entry.currentEditor = null
        for (t in 0 until valueCount) {
          fileSystem.delete(entry.cleanFiles[t])
          fileSystem.delete(entry.dirtyFiles[t])
        }
        i.remove()
      }
    }
  }

  /**
   * Creates a new journal that omits redundant information. This replaces the current journal if it
   * exists.
   */
  @Synchronized @Throws(IOException::class)
  internal fun rebuildJournal() {
    journalWriter?.close()

    fileSystem.sink(journalFileTmp).buffer().use { sink ->
      sink.writeUtf8(MAGIC).writeByte('\n'.toInt())
      sink.writeUtf8(VERSION_1).writeByte('\n'.toInt())
      sink.writeDecimalLong(appVersion.toLong()).writeByte('\n'.toInt())
      sink.writeDecimalLong(valueCount.toLong()).writeByte('\n'.toInt())
      sink.writeByte('\n'.toInt())

      for (entry in lruEntries.values) {
        if (entry.currentEditor != null) {
          sink.writeUtf8(DIRTY).writeByte(' '.toInt())
          sink.writeUtf8(entry.key)
          sink.writeByte('\n'.toInt())
        } else {
          sink.writeUtf8(CLEAN).writeByte(' '.toInt())
          sink.writeUtf8(entry.key)
          entry.writeLengths(sink)
          sink.writeByte('\n'.toInt())
        }
      }
    }

    if (fileSystem.exists(journalFile)) {
      fileSystem.rename(journalFile, journalFileBackup)
    }
    fileSystem.rename(journalFileTmp, journalFile)
    fileSystem.delete(journalFileBackup)

    journalWriter = newJournalWriter()
    hasJournalErrors = false
    mostRecentRebuildFailed = false
  }

  /**
   * Returns a snapshot of the entry named [key], or null if it doesn't exist is not currently
   * readable. If a value is returned, it is moved to the head of the LRU queue.
   */
  @Synchronized @Throws(IOException::class)
  operator fun get(key: String): Snapshot? {
    initialize()

    checkNotClosed()
    validateKey(key)
    val entry = lruEntries[key] ?: return null
    if (!entry.readable) return null
    val snapshot = entry.snapshot() ?: return null

    redundantOpCount++
    journalWriter!!.writeUtf8(READ)
        .writeByte(' '.toInt())
        .writeUtf8(key)
        .writeByte('\n'.toInt())
    if (journalRebuildRequired()) {
      cleanupQueue.schedule(cleanupTask)
    }

    return snapshot
  }

  /** Returns an editor for the entry named [key], or null if another edit is in progress. */
  @Synchronized @Throws(IOException::class)
  @JvmOverloads
  fun edit(key: String, expectedSequenceNumber: Long = ANY_SEQUENCE_NUMBER): Editor? {
    initialize()

    checkNotClosed()
    validateKey(key)
    var entry: Entry? = lruEntries[key]
    if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER &&
        (entry == null || entry.sequenceNumber != expectedSequenceNumber)) {
      return null // Snapshot is stale.
    }

    if (entry?.currentEditor != null) {
      return null // Another edit is in progress.
    }

    if (mostRecentTrimFailed || mostRecentRebuildFailed) {
      // The OS has become our enemy! If the trim job failed, it means we are storing more data than
      // requested by the user. Do not allow edits so we do not go over that limit any further. If
      // the journal rebuild failed, the journal writer will not be active, meaning we will not be
      // able to record the edit, causing file leaks. In both cases, we want to retry the clean up
      // so we can get out of this state!
      cleanupQueue.schedule(cleanupTask)
      return null
    }

    // Flush the journal before creating files to prevent file leaks.
    val journalWriter = this.journalWriter!!
    journalWriter.writeUtf8(DIRTY)
        .writeByte(' '.toInt())
        .writeUtf8(key)
        .writeByte('\n'.toInt())
    journalWriter.flush()

    if (hasJournalErrors) {
      return null // Don't edit; the journal can't be written.
    }

    if (entry == null) {
      entry = Entry(key)
      lruEntries[key] = entry
    }
    val editor = Editor(entry)
    entry.currentEditor = editor
    return editor
  }

  /**
   * Returns the number of bytes currently being used to store the values in this cache. This may be
   * greater than the max size if a background deletion is pending.
   */
  @Synchronized @Throws(IOException::class)
  fun size(): Long {
    initialize()
    return size
  }

  @Synchronized @Throws(IOException::class)
  internal fun completeEdit(editor: Editor, success: Boolean) {
    val entry = editor.entry
    check(entry.currentEditor == editor)

    // If this edit is creating the entry for the first time, every index must have a value.
    if (success && !entry.readable) {
      for (i in 0 until valueCount) {
        if (!editor.written!![i]) {
          editor.abort()
          throw IllegalStateException("Newly created entry didn't create value for index $i")
        }
        if (!fileSystem.exists(entry.dirtyFiles[i])) {
          editor.abort()
          return
        }
      }
    }

    for (i in 0 until valueCount) {
      val dirty = entry.dirtyFiles[i]
      if (success) {
        if (fileSystem.exists(dirty)) {
          val clean = entry.cleanFiles[i]
          fileSystem.rename(dirty, clean)
          val oldLength = entry.lengths[i]
          val newLength = fileSystem.size(clean)
          entry.lengths[i] = newLength
          size = size - oldLength + newLength
        }
      } else {
        fileSystem.delete(dirty)
      }
    }

    redundantOpCount++
    entry.currentEditor = null
    journalWriter!!.apply {
      if (entry.readable || success) {
        entry.readable = true
        writeUtf8(CLEAN).writeByte(' '.toInt())
        writeUtf8(entry.key)
        entry.writeLengths(this)
        writeByte('\n'.toInt())
        if (success) {
          entry.sequenceNumber = nextSequenceNumber++
        }
      } else {
        lruEntries.remove(entry.key)
        writeUtf8(REMOVE).writeByte(' '.toInt())
        writeUtf8(entry.key)
        writeByte('\n'.toInt())
      }
      flush()
    }

    if (size > maxSize || journalRebuildRequired()) {
      cleanupQueue.schedule(cleanupTask)
    }
  }

  /**
   * We only rebuild the journal when it will halve the size of the journal and eliminate at least
   * 2000 ops.
   */
  private fun journalRebuildRequired(): Boolean {
    val redundantOpCompactThreshold = 2000
    return redundantOpCount >= redundantOpCompactThreshold &&
        redundantOpCount >= lruEntries.size
  }

  /**
   * Drops the entry for [key] if it exists and can be removed. If the entry for [key] is currently
   * being edited, that edit will complete normally but its value will not be stored.
   *
   * @return true if an entry was removed.
   */
  @Synchronized @Throws(IOException::class)
  fun remove(key: String): Boolean {
    initialize()

    checkNotClosed()
    validateKey(key)
    val entry = lruEntries[key] ?: return false
    val removed = removeEntry(entry)
    if (removed && size <= maxSize) mostRecentTrimFailed = false
    return removed
  }

  @Throws(IOException::class)
  internal fun removeEntry(entry: Entry): Boolean {
    entry.currentEditor?.detach() // Prevent the edit from completing normally.

    for (i in 0 until valueCount) {
      fileSystem.delete(entry.cleanFiles[i])
      size -= entry.lengths[i]
      entry.lengths[i] = 0
    }

    redundantOpCount++
    journalWriter!!.writeUtf8(REMOVE)
        .writeByte(' '.toInt())
        .writeUtf8(entry.key)
        .writeByte('\n'.toInt())
    lruEntries.remove(entry.key)

    if (journalRebuildRequired()) {
      cleanupQueue.schedule(cleanupTask)
    }

    return true
  }

  @Synchronized private fun checkNotClosed() {
    check(!closed) { "cache is closed" }
  }

  /** Force buffered operations to the filesystem. */
  @Synchronized @Throws(IOException::class)
  override fun flush() {
    if (!initialized) return

    checkNotClosed()
    trimToSize()
    journalWriter!!.flush()
  }

  @Synchronized fun isClosed(): Boolean = closed

  /** Closes this cache. Stored values will remain on the filesystem. */
  @Synchronized @Throws(IOException::class)
  override fun close() {
    if (!initialized || closed) {
      closed = true
      return
    }

    // Copying for concurrent iteration.
    for (entry in lruEntries.values.toTypedArray()) {
      if (entry.currentEditor != null) {
        entry.currentEditor!!.abort()
      }
    }

    trimToSize()
    journalWriter!!.close()
    journalWriter = null
    closed = true
  }

  @Throws(IOException::class)
  fun trimToSize() {
    while (size > maxSize) {
      val toEvict = lruEntries.values.iterator().next()
      removeEntry(toEvict)
    }
    mostRecentTrimFailed = false
  }

  /**
   * Closes the cache and deletes all of its stored values. This will delete all files in the cache
   * directory including files that weren't created by the cache.
   */
  @Throws(IOException::class)
  fun delete() {
    close()
    fileSystem.deleteContents(directory)
  }

  /**
   * Deletes all stored values from the cache. In-flight edits will complete normally but their
   * values will not be stored.
   */
  @Synchronized @Throws(IOException::class)
  fun evictAll() {
    initialize()
    // Copying for concurrent iteration.
    for (entry in lruEntries.values.toTypedArray()) {
      removeEntry(entry)
    }
    mostRecentTrimFailed = false
  }

  private fun validateKey(key: String) {
    require(LEGAL_KEY_PATTERN.matches(key)) { "keys must match regex [a-z0-9_-]{1,120}: \"$key\"" }
  }

  /**
   * Returns an iterator over the cache's current entries. This iterator doesn't throw
   * `ConcurrentModificationException`, but if new entries are added while iterating, those new
   * entries will not be returned by the iterator. If existing entries are removed during iteration,
   * they will be absent (unless they were already returned).
   *
   * If there are I/O problems during iteration, this iterator fails silently. For example, if the
   * hosting filesystem becomes unreachable, the iterator will omit elements rather than throwing
   * exceptions.
   *
   * **The caller must [close][Snapshot.close]** each snapshot returned by [Iterator.next]. Failing
   * to do so leaks open files!
   */
  @Synchronized @Throws(IOException::class)
  fun snapshots(): MutableIterator<Snapshot> {
    initialize()
    return object : MutableIterator<Snapshot> {
      /** Iterate a copy of the entries to defend against concurrent modification errors. */
      val delegate = ArrayList(lruEntries.values).iterator()

      /** The snapshot to return from [next]. Null if we haven't computed that yet. */
      var nextSnapshot: Snapshot? = null

      /** The snapshot to remove with [remove]. Null if removal is illegal. */
      var removeSnapshot: Snapshot? = null

      override fun hasNext(): Boolean {
        if (nextSnapshot != null) return true

        synchronized(this@DiskLruCache) {
          // If the cache is closed, truncate the iterator.
          if (closed) return false

          while (delegate.hasNext()) {
            val entry = delegate.next()
            if (entry == null || !entry.readable) continue // Entry during edit

            val snapshot = entry.snapshot() ?: continue
            // Evicted since we copied the entries.
            nextSnapshot = snapshot
            return true
          }
        }

        return false
      }

      override fun next(): Snapshot {
        if (!hasNext()) throw NoSuchElementException()
        removeSnapshot = nextSnapshot
        nextSnapshot = null
        return removeSnapshot!!
      }

      override fun remove() {
        val removeSnapshot = this.removeSnapshot
        checkNotNull(removeSnapshot) { "remove() before next()" }
        try {
          this@DiskLruCache.remove(removeSnapshot.key())
        } catch (_: IOException) {
          // Nothing useful to do here. We failed to remove from the cache. Most likely that's
          // because we couldn't update the journal, but the cached entry will still be gone.
        } finally {
          this.removeSnapshot = null
        }
      }
    }
  }

  /** A snapshot of the values for an entry. */
  inner class Snapshot internal constructor(
    private val key: String,
    private val sequenceNumber: Long,
    private val sources: List<Source>,
    private val lengths: LongArray
  ) : Closeable {
    fun key(): String = key

    /**
     * Returns an editor for this snapshot's entry, or null if either the entry has changed since
     * this snapshot was created or if another edit is in progress.
     */
    @Throws(IOException::class)
    fun edit(): Editor? = this@DiskLruCache.edit(key, sequenceNumber)

    /** Returns the unbuffered stream with the value for [index]. */
    fun getSource(index: Int): Source = sources[index]

    /** Returns the byte length of the value for [index]. */
    fun getLength(index: Int): Long = lengths[index]

    override fun close() {
      for (source in sources) {
        source.closeQuietly()
      }
    }
  }

  /** Edits the values for an entry. */
  inner class Editor internal constructor(internal val entry: Entry) {
    internal val written: BooleanArray? = if (entry.readable) null else BooleanArray(valueCount)
    private var done: Boolean = false

    /**
     * Prevents this editor from completing normally. This is necessary either when the edit causes
     * an I/O error, or if the target entry is evicted while this editor is active. In either case
     * we delete the editor's created files and prevent new files from being created. Note that once
     * an editor has been detached it is possible for another editor to edit the entry.
     */
    internal fun detach() {
      if (entry.currentEditor == this) {
        for (i in 0 until valueCount) {
          try {
            fileSystem.delete(entry.dirtyFiles[i])
          } catch (_: IOException) {
            // This file is potentially leaked. Not much we can do about that.
          }
        }
        entry.currentEditor = null
      }
    }

    /**
     * Returns an unbuffered input stream to read the last committed value, or null if no value has
     * been committed.
     */
    fun newSource(index: Int): Source? {
      synchronized(this@DiskLruCache) {
        check(!done)
        if (!entry.readable || entry.currentEditor != this) {
          return null
        }
        return try {
          fileSystem.source(entry.cleanFiles[index])
        } catch (_: FileNotFoundException) {
          null
        }
      }
    }

    /**
     * Returns a new unbuffered output stream to write the value at [index]. If the underlying
     * output stream encounters errors when writing to the filesystem, this edit will be aborted
     * when [commit] is called. The returned output stream does not throw IOExceptions.
     */
    fun newSink(index: Int): Sink {
      synchronized(this@DiskLruCache) {
        check(!done)
        if (entry.currentEditor != this) {
          return blackholeSink()
        }
        if (!entry.readable) {
          written!![index] = true
        }
        val dirtyFile = entry.dirtyFiles[index]
        val sink: Sink
        try {
          sink = fileSystem.sink(dirtyFile)
        } catch (_: FileNotFoundException) {
          return blackholeSink()
        }
        return FaultHidingSink(sink) {
          synchronized(this@DiskLruCache) {
            detach()
          }
        }
      }
    }

    /**
     * Commits this edit so it is visible to readers. This releases the edit lock so another edit
     * may be started on the same key.
     */
    @Throws(IOException::class)
    fun commit() {
      synchronized(this@DiskLruCache) {
        check(!done)
        if (entry.currentEditor == this) {
          completeEdit(this, true)
        }
        done = true
      }
    }

    /**
     * Aborts this edit. This releases the edit lock so another edit may be started on the same
     * key.
     */
    @Throws(IOException::class)
    fun abort() {
      synchronized(this@DiskLruCache) {
        check(!done)
        if (entry.currentEditor == this) {
          completeEdit(this, false)
        }
        done = true
      }
    }
  }

  internal inner class Entry internal constructor(
    internal val key: String
  ) {

    /** Lengths of this entry's files. */
    internal val lengths: LongArray = LongArray(valueCount)
    internal val cleanFiles = mutableListOf<File>()
    internal val dirtyFiles = mutableListOf<File>()

    /** True if this entry has ever been published. */
    internal var readable: Boolean = false

    /** The ongoing edit or null if this entry is not being edited. */
    internal var currentEditor: Editor? = null

    /** The sequence number of the most recently committed edit to this entry. */
    internal var sequenceNumber: Long = 0

    init {
      // The names are repetitive so re-use the same builder to avoid allocations.
      val fileBuilder = StringBuilder(key).append('.')
      val truncateTo = fileBuilder.length
      for (i in 0 until valueCount) {
        fileBuilder.append(i)
        cleanFiles += File(directory, fileBuilder.toString())
        fileBuilder.append(".tmp")
        dirtyFiles += File(directory, fileBuilder.toString())
        fileBuilder.setLength(truncateTo)
      }
    }

    /** Set lengths using decimal numbers like "10123". */
    @Throws(IOException::class)
    internal fun setLengths(strings: List<String>) {
      if (strings.size != valueCount) {
        throw invalidLengths(strings)
      }

      try {
        for (i in strings.indices) {
          lengths[i] = strings[i].toLong()
        }
      } catch (_: NumberFormatException) {
        throw invalidLengths(strings)
      }
    }

    /** Append space-prefixed lengths to [writer]. */
    @Throws(IOException::class)
    internal fun writeLengths(writer: BufferedSink) {
      for (length in lengths) {
        writer.writeByte(' '.toInt()).writeDecimalLong(length)
      }
    }

    @Throws(IOException::class)
    private fun invalidLengths(strings: List<String>): IOException {
      throw IOException("unexpected journal line: $strings")
    }

    /**
     * Returns a snapshot of this entry. This opens all streams eagerly to guarantee that we see a
     * single published snapshot. If we opened streams lazily then the streams could come from
     * different edits.
     */
    internal fun snapshot(): Snapshot? {
      this@DiskLruCache.assertThreadHoldsLock()

      val sources = mutableListOf<Source>()
      val lengths = this.lengths.clone() // Defensive copy since these can be zeroed out.
      try {
        for (i in 0 until valueCount) {
          sources += fileSystem.source(cleanFiles[i])
        }
        return Snapshot(key, sequenceNumber, sources, lengths)
      } catch (_: FileNotFoundException) {
        // A file must have been deleted manually!
        for (source in sources) {
          source.closeQuietly()
        }
        // Since the entry is no longer valid, remove it so the metadata is accurate (i.e. the cache
        // size.)
        try {
          removeEntry(this)
        } catch (_: IOException) {
        }
        return null
      }
    }
  }

  companion object {
    @JvmField val JOURNAL_FILE = "journal"
    @JvmField val JOURNAL_FILE_TEMP = "journal.tmp"
    @JvmField val JOURNAL_FILE_BACKUP = "journal.bkp"
    @JvmField val MAGIC = "libcore.io.DiskLruCache"
    @JvmField val VERSION_1 = "1"
    @JvmField val ANY_SEQUENCE_NUMBER: Long = -1
    @JvmField val LEGAL_KEY_PATTERN = "[a-z0-9_-]{1,120}".toRegex()
    @JvmField val CLEAN = "CLEAN"
    @JvmField val DIRTY = "DIRTY"
    @JvmField val REMOVE = "REMOVE"
    @JvmField val READ = "READ"
  }
}
