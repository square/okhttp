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
package okhttp3.internal.cache;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import okhttp3.internal.io.FaultyFileSystem;
import okhttp3.internal.io.FileSystem;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import static okhttp3.internal.cache.DiskLruCache.JOURNAL_FILE;
import static okhttp3.internal.cache.DiskLruCache.JOURNAL_FILE_BACKUP;
import static okhttp3.internal.cache.DiskLruCache.MAGIC;
import static okhttp3.internal.cache.DiskLruCache.VERSION_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DiskLruCacheTest {
  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public final Timeout timeout = new Timeout(60 * 1000);

  private final FaultyFileSystem fileSystem = new FaultyFileSystem(FileSystem.SYSTEM);
  private final int appVersion = 100;
  private File cacheDir;
  private File journalFile;
  private File journalBkpFile;
  private final TestExecutor executor = new TestExecutor();

  private DiskLruCache cache;
  private final Deque<DiskLruCache> toClose = new ArrayDeque<>();

  private void createNewCache() throws IOException {
    createNewCacheWithSize(Integer.MAX_VALUE);
  }

  private void createNewCacheWithSize(int maxSize) throws IOException {
    cache = new DiskLruCache(fileSystem, cacheDir, appVersion, 2, maxSize, executor);
    synchronized (cache) {
      cache.initialize();
    }
    toClose.add(cache);
  }

  @Before public void setUp() throws Exception {
    cacheDir = tempDir.getRoot();
    journalFile = new File(cacheDir, JOURNAL_FILE);
    journalBkpFile = new File(cacheDir, JOURNAL_FILE_BACKUP);
    createNewCache();
  }

  @After public void tearDown() throws Exception {
    while (!toClose.isEmpty()) {
      toClose.pop().close();
    }
  }

  @Test public void emptyCache() throws Exception {
    cache.close();
    assertJournalEquals();
  }

  @Test public void recoverFromInitializationFailure() throws IOException {
    // Add an uncommitted entry. This will get detected on initialization, and the cache will
    // attempt to delete the file. Do not explicitly close the cache here so the entry is left as
    // incomplete.
    DiskLruCache.Editor creator = cache.edit("k1");
    BufferedSink sink = Okio.buffer(creator.newSink(0));
    sink.writeUtf8("Hello");
    sink.close();

    // Simulate a severe filesystem failure on the first initialization.
    fileSystem.setFaultyDelete(new File(cacheDir, "k1.0.tmp"), true);
    fileSystem.setFaultyDelete(cacheDir, true);

    cache = new DiskLruCache(fileSystem, cacheDir, appVersion, 2, Integer.MAX_VALUE, executor);
    toClose.add(cache);

    try {
      cache.get("k1");
      fail();
    } catch (IOException expected) {
    }

    // Now let it operate normally.
    fileSystem.setFaultyDelete(new File(cacheDir, "k1.0.tmp"), false);
    fileSystem.setFaultyDelete(cacheDir, false);

    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertNull(snapshot);
  }

  @Test public void validateKey() throws Exception {
    String key = null;
    try {
      key = "has_space ";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "has_CR\r";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "has_LF\n";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "has_invalid/";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "has_invalid\u2603";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long_"
          + "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was too long.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }

    // Test valid cases.

    // Exactly 120.
    key = "0123456789012345678901234567890123456789012345678901234567890123456789"
        + "01234567890123456789012345678901234567890123456789";
    cache.edit(key).abort();
    // Contains all valid characters.
    key = "abcdefghijklmnopqrstuvwxyz_0123456789";
    cache.edit(key).abort();
    // Contains dash.
    key = "-20384573948576";
    cache.edit(key).abort();
  }

  @Test public void writeAndReadEntry() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "ABC");
    setString(creator, 1, "DE");
    assertNull(creator.newSource(0));
    assertNull(creator.newSource(1));
    creator.commit();

    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, 0, "ABC");
    assertSnapshotValue(snapshot, 1, "DE");
  }

  @Test public void readAndWriteEntryAcrossCacheOpenAndClose() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "A");
    setString(creator, 1, "B");
    creator.commit();
    cache.close();

    createNewCache();
    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, 0, "A");
    assertSnapshotValue(snapshot, 1, "B");
    snapshot.close();
  }

  @Test public void readAndWriteEntryWithoutProperClose() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "A");
    setString(creator, 1, "B");
    creator.commit();

    // Simulate a dirty close of 'cache' by opening the cache directory again.
    createNewCache();
    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, 0, "A");
    assertSnapshotValue(snapshot, 1, "B");
    snapshot.close();
  }

  @Test public void journalWithEditAndPublish() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    assertJournalEquals("DIRTY k1"); // DIRTY must always be flushed.
    setString(creator, 0, "AB");
    setString(creator, 1, "C");
    creator.commit();
    cache.close();
    assertJournalEquals("DIRTY k1", "CLEAN k1 2 1");
  }

  @Test public void revertedNewFileIsRemoveInJournal() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    assertJournalEquals("DIRTY k1"); // DIRTY must always be flushed.
    setString(creator, 0, "AB");
    setString(creator, 1, "C");
    creator.abort();
    cache.close();
    assertJournalEquals("DIRTY k1", "REMOVE k1");
  }

  @Test public void unterminatedEditIsRevertedOnClose() throws Exception {
    cache.edit("k1");
    cache.close();
    assertJournalEquals("DIRTY k1", "REMOVE k1");
  }

  @Test public void journalDoesNotIncludeReadOfYetUnpublishedValue() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    assertNull(cache.get("k1"));
    setString(creator, 0, "A");
    setString(creator, 1, "BC");
    creator.commit();
    cache.close();
    assertJournalEquals("DIRTY k1", "CLEAN k1 1 2");
  }

  @Test public void journalWithEditAndPublishAndRead() throws Exception {
    DiskLruCache.Editor k1Creator = cache.edit("k1");
    setString(k1Creator, 0, "AB");
    setString(k1Creator, 1, "C");
    k1Creator.commit();
    DiskLruCache.Editor k2Creator = cache.edit("k2");
    setString(k2Creator, 0, "DEF");
    setString(k2Creator, 1, "G");
    k2Creator.commit();
    DiskLruCache.Snapshot k1Snapshot = cache.get("k1");
    k1Snapshot.close();
    cache.close();
    assertJournalEquals("DIRTY k1", "CLEAN k1 2 1", "DIRTY k2", "CLEAN k2 3 1", "READ k1");
  }

  @Test public void cannotOperateOnEditAfterPublish() throws Exception {
    DiskLruCache.Editor editor = cache.edit("k1");
    setString(editor, 0, "A");
    setString(editor, 1, "B");
    editor.commit();
    assertInoperable(editor);
  }

  @Test public void cannotOperateOnEditAfterRevert() throws Exception {
    DiskLruCache.Editor editor = cache.edit("k1");
    setString(editor, 0, "A");
    setString(editor, 1, "B");
    editor.abort();
    assertInoperable(editor);
  }

  @Test public void explicitRemoveAppliedToDiskImmediately() throws Exception {
    DiskLruCache.Editor editor = cache.edit("k1");
    setString(editor, 0, "ABC");
    setString(editor, 1, "B");
    editor.commit();
    File k1 = getCleanFile("k1", 0);
    assertEquals("ABC", readFile(k1));
    cache.remove("k1");
    assertFalse(fileSystem.exists(k1));
  }

  @Test public void removePreventsActiveEditFromStoringAValue() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Editor a = cache.edit("a");
    setString(a, 0, "a1");
    assertTrue(cache.remove("a"));
    setString(a, 1, "a2");
    a.commit();
    assertAbsent("a");
  }

  /**
   * Each read sees a snapshot of the file at the time read was called. This means that two reads of
   * the same key can see different data.
   */
  @Test public void readAndWriteOverlapsMaintainConsistency() throws Exception {
    DiskLruCache.Editor v1Creator = cache.edit("k1");
    setString(v1Creator, 0, "AAaa");
    setString(v1Creator, 1, "BBbb");
    v1Creator.commit();

    DiskLruCache.Snapshot snapshot1 = cache.get("k1");
    BufferedSource inV1 = Okio.buffer(snapshot1.getSource(0));
    assertEquals('A', inV1.readByte());
    assertEquals('A', inV1.readByte());

    DiskLruCache.Editor v1Updater = cache.edit("k1");
    setString(v1Updater, 0, "CCcc");
    setString(v1Updater, 1, "DDdd");
    v1Updater.commit();

    DiskLruCache.Snapshot snapshot2 = cache.get("k1");
    assertSnapshotValue(snapshot2, 0, "CCcc");
    assertSnapshotValue(snapshot2, 1, "DDdd");
    snapshot2.close();

    assertEquals('a', inV1.readByte());
    assertEquals('a', inV1.readByte());
    assertSnapshotValue(snapshot1, 1, "BBbb");
    snapshot1.close();
  }

  @Test public void openWithDirtyKeyDeletesAllFilesForThatKey() throws Exception {
    cache.close();
    File cleanFile0 = getCleanFile("k1", 0);
    File cleanFile1 = getCleanFile("k1", 1);
    File dirtyFile0 = getDirtyFile("k1", 0);
    File dirtyFile1 = getDirtyFile("k1", 1);
    writeFile(cleanFile0, "A");
    writeFile(cleanFile1, "B");
    writeFile(dirtyFile0, "C");
    writeFile(dirtyFile1, "D");
    createJournal("CLEAN k1 1 1", "DIRTY   k1");
    createNewCache();
    assertFalse(fileSystem.exists(cleanFile0));
    assertFalse(fileSystem.exists(cleanFile1));
    assertFalse(fileSystem.exists(dirtyFile0));
    assertFalse(fileSystem.exists(dirtyFile1));
    assertNull(cache.get("k1"));
  }

  @Test public void openWithInvalidVersionClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournalWithHeader(MAGIC, "0", "100", "2", "");
    createNewCache();
    assertGarbageFilesAllDeleted();
  }

  @Test public void openWithInvalidAppVersionClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournalWithHeader(MAGIC, "1", "101", "2", "");
    createNewCache();
    assertGarbageFilesAllDeleted();
  }

  @Test public void openWithInvalidValueCountClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournalWithHeader(MAGIC, "1", "100", "1", "");
    createNewCache();
    assertGarbageFilesAllDeleted();
  }

  @Test public void openWithInvalidBlankLineClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournalWithHeader(MAGIC, "1", "100", "2", "x");
    createNewCache();
    assertGarbageFilesAllDeleted();
  }

  @Test public void openWithInvalidJournalLineClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 1 1", "BOGUS");
    createNewCache();
    assertGarbageFilesAllDeleted();
    assertNull(cache.get("k1"));
  }

  @Test public void openWithInvalidFileSizeClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 0000x001 1");
    createNewCache();
    assertGarbageFilesAllDeleted();
    assertNull(cache.get("k1"));
  }

  @Test public void openWithTruncatedLineDiscardsThatLine() throws Exception {
    cache.close();
    writeFile(getCleanFile("k1", 0), "A");
    writeFile(getCleanFile("k1", 1), "B");

    BufferedSink sink = Okio.buffer(fileSystem.sink(journalFile));
    sink.writeUtf8(MAGIC + "\n" + VERSION_1 + "\n100\n2\n\nCLEAN k1 1 1"); // no trailing newline
    sink.close();
    createNewCache();
    assertNull(cache.get("k1"));

    // The journal is not corrupt when editing after a truncated line.
    set("k1", "C", "D");

    cache.close();
    createNewCache();
    assertValue("k1", "C", "D");
  }

  @Test public void openWithTooManyFileSizesClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 1 1 1");
    createNewCache();
    assertGarbageFilesAllDeleted();
    assertNull(cache.get("k1"));
  }

  @Test public void keyWithSpaceNotPermitted() throws Exception {
    try {
      cache.edit("my key");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void keyWithNewlineNotPermitted() throws Exception {
    try {
      cache.edit("my\nkey");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void keyWithCarriageReturnNotPermitted() throws Exception {
    try {
      cache.edit("my\rkey");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void nullKeyThrows() throws Exception {
    try {
      cache.edit(null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test public void createNewEntryWithTooFewValuesFails() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 1, "A");
    try {
      creator.commit();
      fail();
    } catch (IllegalStateException expected) {
    }

    assertFalse(fileSystem.exists(getCleanFile("k1", 0)));
    assertFalse(fileSystem.exists(getCleanFile("k1", 1)));
    assertFalse(fileSystem.exists(getDirtyFile("k1", 0)));
    assertFalse(fileSystem.exists(getDirtyFile("k1", 1)));
    assertNull(cache.get("k1"));

    DiskLruCache.Editor creator2 = cache.edit("k1");
    setString(creator2, 0, "B");
    setString(creator2, 1, "C");
    creator2.commit();
  }

  @Test public void revertWithTooFewValues() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 1, "A");
    creator.abort();
    assertFalse(fileSystem.exists(getCleanFile("k1", 0)));
    assertFalse(fileSystem.exists(getCleanFile("k1", 1)));
    assertFalse(fileSystem.exists(getDirtyFile("k1", 0)));
    assertFalse(fileSystem.exists(getDirtyFile("k1", 1)));
    assertNull(cache.get("k1"));
  }

  @Test public void updateExistingEntryWithTooFewValuesReusesPreviousValues() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "A");
    setString(creator, 1, "B");
    creator.commit();

    DiskLruCache.Editor updater = cache.edit("k1");
    setString(updater, 0, "C");
    updater.commit();

    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, 0, "C");
    assertSnapshotValue(snapshot, 1, "B");
    snapshot.close();
  }

  @Test public void growMaxSize() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "a", "aaa"); // size 4
    set("b", "bb", "bbbb"); // size 6
    cache.setMaxSize(20);
    set("c", "c", "c"); // size 12
    assertEquals(12, cache.size());
  }

  @Test public void shrinkMaxSizeEvicts() throws Exception {
    cache.close();
    createNewCacheWithSize(20);
    set("a", "a", "aaa"); // size 4
    set("b", "bb", "bbbb"); // size 6
    set("c", "c", "c"); // size 12
    cache.setMaxSize(10);
    assertEquals(1, executor.jobs.size());
  }

  @Test public void evictOnInsert() throws Exception {
    cache.close();
    createNewCacheWithSize(10);

    set("a", "a", "aaa"); // size 4
    set("b", "bb", "bbbb"); // size 6
    assertEquals(10, cache.size());

    // Cause the size to grow to 12 should evict 'A'.
    set("c", "c", "c");
    cache.flush();
    assertEquals(8, cache.size());
    assertAbsent("a");
    assertValue("b", "bb", "bbbb");
    assertValue("c", "c", "c");

    // Causing the size to grow to 10 should evict nothing.
    set("d", "d", "d");
    cache.flush();
    assertEquals(10, cache.size());
    assertAbsent("a");
    assertValue("b", "bb", "bbbb");
    assertValue("c", "c", "c");
    assertValue("d", "d", "d");

    // Causing the size to grow to 18 should evict 'B' and 'C'.
    set("e", "eeee", "eeee");
    cache.flush();
    assertEquals(10, cache.size());
    assertAbsent("a");
    assertAbsent("b");
    assertAbsent("c");
    assertValue("d", "d", "d");
    assertValue("e", "eeee", "eeee");
  }

  @Test public void evictOnUpdate() throws Exception {
    cache.close();
    createNewCacheWithSize(10);

    set("a", "a", "aa"); // size 3
    set("b", "b", "bb"); // size 3
    set("c", "c", "cc"); // size 3
    assertEquals(9, cache.size());

    // Causing the size to grow to 11 should evict 'A'.
    set("b", "b", "bbbb");
    cache.flush();
    assertEquals(8, cache.size());
    assertAbsent("a");
    assertValue("b", "b", "bbbb");
    assertValue("c", "c", "cc");
  }

  @Test public void evictionHonorsLruFromCurrentSession() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "a", "a");
    set("b", "b", "b");
    set("c", "c", "c");
    set("d", "d", "d");
    set("e", "e", "e");
    cache.get("b").close(); // 'B' is now least recently used.

    // Causing the size to grow to 12 should evict 'A'.
    set("f", "f", "f");
    // Causing the size to grow to 12 should evict 'C'.
    set("g", "g", "g");
    cache.flush();
    assertEquals(10, cache.size());
    assertAbsent("a");
    assertValue("b", "b", "b");
    assertAbsent("c");
    assertValue("d", "d", "d");
    assertValue("e", "e", "e");
    assertValue("f", "f", "f");
  }

  @Test public void evictionHonorsLruFromPreviousSession() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    set("c", "c", "c");
    set("d", "d", "d");
    set("e", "e", "e");
    set("f", "f", "f");
    cache.get("b").close(); // 'B' is now least recently used.
    assertEquals(12, cache.size());
    cache.close();
    createNewCacheWithSize(10);

    set("g", "g", "g");
    cache.flush();
    assertEquals(10, cache.size());
    assertAbsent("a");
    assertValue("b", "b", "b");
    assertAbsent("c");
    assertValue("d", "d", "d");
    assertValue("e", "e", "e");
    assertValue("f", "f", "f");
    assertValue("g", "g", "g");
  }

  @Test public void cacheSingleEntryOfSizeGreaterThanMaxSize() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "aaaaa", "aaaaaa"); // size=11
    cache.flush();
    assertAbsent("a");
  }

  @Test public void cacheSingleValueOfSizeGreaterThanMaxSize() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "aaaaaaaaaaa", "a"); // size=12
    cache.flush();
    assertAbsent("a");
  }

  @Test public void constructorDoesNotAllowZeroCacheSize() throws Exception {
    try {
      DiskLruCache.create(fileSystem, cacheDir, appVersion, 2, 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void constructorDoesNotAllowZeroValuesPerEntry() throws Exception {
    try {
      DiskLruCache.create(fileSystem, cacheDir, appVersion, 0, 10);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void removeAbsentElement() throws Exception {
    cache.remove("a");
  }

  @Test public void readingTheSameStreamMultipleTimes() throws Exception {
    set("a", "a", "b");
    DiskLruCache.Snapshot snapshot = cache.get("a");
    assertSame(snapshot.getSource(0), snapshot.getSource(0));
    snapshot.close();
  }

  @Test public void rebuildJournalOnRepeatedReads() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    while (executor.jobs.isEmpty()) {
      assertValue("a", "a", "a");
      assertValue("b", "b", "b");
    }
  }

  @Test public void rebuildJournalOnRepeatedEdits() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }
    executor.jobs.removeFirst().run();

    // Sanity check that a rebuilt journal behaves normally.
    assertValue("a", "a", "a");
    assertValue("b", "b", "b");
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/28">Issue #28</a> */
  @Test public void rebuildJournalOnRepeatedReadsWithOpenAndClose() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    while (executor.jobs.isEmpty()) {
      assertValue("a", "a", "a");
      assertValue("b", "b", "b");
      cache.close();
      createNewCache();
    }
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/28">Issue #28</a> */
  @Test public void rebuildJournalOnRepeatedEditsWithOpenAndClose() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
      cache.close();
      createNewCache();
    }
  }

  @Test public void rebuildJournalFailurePreventsEditors() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }

    // Cause the rebuild action to fail.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), true);
    executor.jobs.removeFirst().run();

    // Don't allow edits under any circumstances.
    assertNull(cache.edit("a"));
    assertNull(cache.edit("c"));
    DiskLruCache.Snapshot snapshot = cache.get("a");
    assertNull(snapshot.edit());
    snapshot.close();
  }

  @Test public void rebuildJournalFailureIsRetried() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }

    // Cause the rebuild action to fail.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), true);
    executor.jobs.removeFirst().run();

    // The rebuild is retried on cache hits and on cache edits.
    DiskLruCache.Snapshot snapshot = cache.get("b");
    snapshot.close();
    assertNull(cache.edit("d"));
    assertEquals(2, executor.jobs.size());

    // On cache misses, no retry job is queued.
    assertNull(cache.get("c"));
    assertEquals(2, executor.jobs.size());

    // Let the rebuild complete successfully.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), false);
    executor.jobs.removeFirst().run();
    assertJournalEquals("CLEAN a 1 1", "CLEAN b 1 1");
  }

  @Test public void rebuildJournalFailureWithInFlightEditors() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }
    DiskLruCache.Editor commitEditor = cache.edit("c");
    DiskLruCache.Editor abortEditor = cache.edit("d");
    cache.edit("e"); // Grab an editor, but don't do anything with it.

    // Cause the rebuild action to fail.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), true);
    executor.jobs.removeFirst().run();

    // In-flight editors can commit and have their values retained.
    setString(commitEditor, 0, "c");
    setString(commitEditor, 1, "c");
    commitEditor.commit();
    assertValue("c", "c", "c");

    abortEditor.abort();

    // Let the rebuild complete successfully.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), false);
    executor.jobs.removeFirst().run();
    assertJournalEquals("CLEAN a 1 1", "CLEAN b 1 1", "DIRTY e", "CLEAN c 1 1");
  }

  @Test public void rebuildJournalFailureWithEditorsInFlightThenClose() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }
    DiskLruCache.Editor commitEditor = cache.edit("c");
    DiskLruCache.Editor abortEditor = cache.edit("d");
    cache.edit("e"); // Grab an editor, but don't do anything with it.

    // Cause the rebuild action to fail.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), true);
    executor.jobs.removeFirst().run();

    setString(commitEditor, 0, "c");
    setString(commitEditor, 1, "c");
    commitEditor.commit();
    assertValue("c", "c", "c");

    abortEditor.abort();

    cache.close();
    createNewCache();

    // Although 'c' successfully committed above, the journal wasn't available to issue a CLEAN op.
    // Because the last state of 'c' was DIRTY before the journal failed, it should be removed
    // entirely on a subsequent open.
    assertEquals(4, cache.size());
    assertAbsent("c");
    assertAbsent("d");
    assertAbsent("e");
  }

  @Test public void rebuildJournalFailureAllowsRemovals() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }

    // Cause the rebuild action to fail.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), true);
    executor.jobs.removeFirst().run();

    assertTrue(cache.remove("a"));
    assertAbsent("a");

    // Let the rebuild complete successfully.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), false);
    executor.jobs.removeFirst().run();

    assertJournalEquals("CLEAN b 1 1");
  }

  @Test public void rebuildJournalFailureWithRemovalThenClose() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }

    // Cause the rebuild action to fail.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), true);
    executor.jobs.removeFirst().run();

    assertTrue(cache.remove("a"));
    assertAbsent("a");

    cache.close();
    createNewCache();

    // The journal will have no record that 'a' was removed. It will have an entry for 'a', but when
    // it tries to read the cache files, it will find they were deleted. Once it encounters an entry
    // with missing cache files, it should remove it from the cache entirely.
    assertEquals(4, cache.size());
    assertNull(cache.get("a"));
    assertEquals(2, cache.size());
  }

  @Test public void rebuildJournalFailureAllowsEvictAll() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }

    // Cause the rebuild action to fail.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), true);
    executor.jobs.removeFirst().run();

    cache.evictAll();

    assertEquals(0, cache.size());
    assertAbsent("a");
    assertAbsent("b");

    cache.close();
    createNewCache();

    // The journal has no record that 'a' and 'b' were removed. It will have an entry for both, but
    // when it tries to read the cache files for either entry, it will discover the cache files are
    // missing and remove the entries from the cache.
    assertEquals(4, cache.size());
    assertNull(cache.get("a"));
    assertNull(cache.get("b"));
    assertEquals(0, cache.size());
  }

  @Test public void rebuildJournalFailureWithCacheTrim() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "aa", "aa");
      set("b", "bb", "bb");
    }

    // Cause the rebuild action to fail.
    fileSystem.setFaultyRename(new File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP), true);
    executor.jobs.removeFirst().run();

    // Trigger a job to trim the cache.
    cache.setMaxSize(4);
    executor.jobs.removeFirst().run();

    assertAbsent("a");
    assertValue("b", "bb", "bb");
  }

  @Test public void restoreBackupFile() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "ABC");
    setString(creator, 1, "DE");
    creator.commit();
    cache.close();

    fileSystem.rename(journalFile, journalBkpFile);
    assertFalse(fileSystem.exists(journalFile));

    createNewCache();

    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, 0, "ABC");
    assertSnapshotValue(snapshot, 1, "DE");

    assertFalse(fileSystem.exists(journalBkpFile));
    assertTrue(fileSystem.exists(journalFile));
  }

  @Test public void journalFileIsPreferredOverBackupFile() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "ABC");
    setString(creator, 1, "DE");
    creator.commit();
    cache.flush();

    copyFile(journalFile, journalBkpFile);

    creator = cache.edit("k2");
    setString(creator, 0, "F");
    setString(creator, 1, "GH");
    creator.commit();
    cache.close();

    assertTrue(fileSystem.exists(journalFile));
    assertTrue(fileSystem.exists(journalBkpFile));

    createNewCache();

    DiskLruCache.Snapshot snapshotA = cache.get("k1");
    assertSnapshotValue(snapshotA, 0, "ABC");
    assertSnapshotValue(snapshotA, 1, "DE");

    DiskLruCache.Snapshot snapshotB = cache.get("k2");
    assertSnapshotValue(snapshotB, 0, "F");
    assertSnapshotValue(snapshotB, 1, "GH");

    assertFalse(fileSystem.exists(journalBkpFile));
    assertTrue(fileSystem.exists(journalFile));
  }

  @Test public void openCreatesDirectoryIfNecessary() throws Exception {
    cache.close();
    File dir = tempDir.newFolder("testOpenCreatesDirectoryIfNecessary");
    cache = DiskLruCache.create(fileSystem, dir, appVersion, 2, Integer.MAX_VALUE);
    set("a", "a", "a");
    assertTrue(fileSystem.exists(new File(dir, "a.0")));
    assertTrue(fileSystem.exists(new File(dir, "a.1")));
    assertTrue(fileSystem.exists(new File(dir, "journal")));
  }

  @Test public void fileDeletedExternally() throws Exception {
    set("a", "a", "a");
    fileSystem.delete(getCleanFile("a", 1));
    assertNull(cache.get("a"));
    assertEquals(0, cache.size());
  }

  @Test public void editSameVersion() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot snapshot = cache.get("a");
    DiskLruCache.Editor editor = snapshot.edit();
    setString(editor, 1, "a2");
    editor.commit();
    assertValue("a", "a", "a2");
  }

  @Test public void editSnapshotAfterChangeAborted() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot snapshot = cache.get("a");
    DiskLruCache.Editor toAbort = snapshot.edit();
    setString(toAbort, 0, "b");
    toAbort.abort();
    DiskLruCache.Editor editor = snapshot.edit();
    setString(editor, 1, "a2");
    editor.commit();
    assertValue("a", "a", "a2");
  }

  @Test public void editSnapshotAfterChangeCommitted() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot snapshot = cache.get("a");
    DiskLruCache.Editor toAbort = snapshot.edit();
    setString(toAbort, 0, "b");
    toAbort.commit();
    assertNull(snapshot.edit());
  }

  @Test public void editSinceEvicted() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "aa", "aaa"); // size 5
    DiskLruCache.Snapshot snapshot = cache.get("a");
    set("b", "bb", "bbb"); // size 5
    set("c", "cc", "ccc"); // size 5; will evict 'A'
    cache.flush();
    assertNull(snapshot.edit());
  }

  @Test public void editSinceEvictedAndRecreated() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "aa", "aaa"); // size 5
    DiskLruCache.Snapshot snapshot = cache.get("a");
    set("b", "bb", "bbb"); // size 5
    set("c", "cc", "ccc"); // size 5; will evict 'A'
    set("a", "a", "aaaa"); // size 5; will evict 'B'
    cache.flush();
    assertNull(snapshot.edit());
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
  @Test public void aggressiveClearingHandlesWrite() throws Exception {
    fileSystem.deleteContents(tempDir.getRoot());
    set("a", "a", "a");
    assertValue("a", "a", "a");
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
  @Test public void aggressiveClearingHandlesEdit() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Editor a = cache.get("a").edit();
    fileSystem.deleteContents(tempDir.getRoot());
    setString(a, 1, "a2");
    a.commit();
  }

  @Test public void removeHandlesMissingFile() throws Exception {
    set("a", "a", "a");
    getCleanFile("a", 0).delete();
    cache.remove("a");
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
  @Test public void aggressiveClearingHandlesPartialEdit() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    DiskLruCache.Editor a = cache.get("a").edit();
    setString(a, 0, "a1");
    fileSystem.deleteContents(tempDir.getRoot());
    setString(a, 1, "a2");
    a.commit();
    assertNull(cache.get("a"));
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
  @Test public void aggressiveClearingHandlesRead() throws Exception {
    fileSystem.deleteContents(tempDir.getRoot());
    assertNull(cache.get("a"));
  }

  /**
   * We had a long-lived bug where {@link DiskLruCache#trimToSize} could infinite loop if entries
   * being edited required deletion for the operation to complete.
   */
  @Test public void trimToSizeWithActiveEdit() throws Exception {
    set("a", "a1234", "a1234");
    DiskLruCache.Editor a = cache.edit("a");
    setString(a, 0, "a123");

    cache.setMaxSize(8); // Smaller than the sum of active edits!
    cache.flush(); // Force trimToSize().
    assertEquals(0, cache.size());
    assertNull(cache.get("a"));

    // After the edit is completed, its entry is still gone.
    setString(a, 1, "a1");
    a.commit();
    assertAbsent("a");
    assertEquals(0, cache.size());
  }

  @Test public void evictAll() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    cache.evictAll();
    assertEquals(0, cache.size());
    assertAbsent("a");
    assertAbsent("b");
  }

  @Test public void evictAllWithPartialCreate() throws Exception {
    DiskLruCache.Editor a = cache.edit("a");
    setString(a, 0, "a1");
    setString(a, 1, "a2");
    cache.evictAll();
    assertEquals(0, cache.size());
    a.commit();
    assertAbsent("a");
  }

  @Test public void evictAllWithPartialEditDoesNotStoreAValue() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Editor a = cache.edit("a");
    setString(a, 0, "a1");
    setString(a, 1, "a2");
    cache.evictAll();
    assertEquals(0, cache.size());
    a.commit();
    assertAbsent("a");
  }

  @Test public void evictAllDoesntInterruptPartialRead() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot a = cache.get("a");
    assertSnapshotValue(a, 0, "a");
    cache.evictAll();
    assertEquals(0, cache.size());
    assertAbsent("a");
    assertSnapshotValue(a, 1, "a");
    a.close();
  }

  @Test public void editSnapshotAfterEvictAllReturnsNullDueToStaleValue() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot a = cache.get("a");
    cache.evictAll();
    assertEquals(0, cache.size());
    assertAbsent("a");
    assertNull(a.edit());
    a.close();
  }

  @Test public void iterator() throws Exception {
    set("a", "a1", "a2");
    set("b", "b1", "b2");
    set("c", "c1", "c2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    assertTrue(iterator.hasNext());
    DiskLruCache.Snapshot a = iterator.next();
    assertEquals("a", a.key());
    assertSnapshotValue(a, 0, "a1");
    assertSnapshotValue(a, 1, "a2");
    a.close();

    assertTrue(iterator.hasNext());
    DiskLruCache.Snapshot b = iterator.next();
    assertEquals("b", b.key());
    assertSnapshotValue(b, 0, "b1");
    assertSnapshotValue(b, 1, "b2");
    b.close();

    assertTrue(iterator.hasNext());
    DiskLruCache.Snapshot c = iterator.next();
    assertEquals("c", c.key());
    assertSnapshotValue(c, 0, "c1");
    assertSnapshotValue(c, 1, "c2");
    c.close();

    assertFalse(iterator.hasNext());
    try {
      iterator.next();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void iteratorElementsAddedDuringIterationAreOmitted() throws Exception {
    set("a", "a1", "a2");
    set("b", "b1", "b2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    DiskLruCache.Snapshot a = iterator.next();
    assertEquals("a", a.key());
    a.close();

    set("c", "c1", "c2");

    DiskLruCache.Snapshot b = iterator.next();
    assertEquals("b", b.key());
    b.close();

    assertFalse(iterator.hasNext());
  }

  @Test public void iteratorElementsUpdatedDuringIterationAreUpdated() throws Exception {
    set("a", "a1", "a2");
    set("b", "b1", "b2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    DiskLruCache.Snapshot a = iterator.next();
    assertEquals("a", a.key());
    a.close();

    set("b", "b3", "b4");

    DiskLruCache.Snapshot b = iterator.next();
    assertEquals("b", b.key());
    assertSnapshotValue(b, 0, "b3");
    assertSnapshotValue(b, 1, "b4");
    b.close();
  }

  @Test public void iteratorElementsRemovedDuringIterationAreOmitted() throws Exception {
    set("a", "a1", "a2");
    set("b", "b1", "b2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    cache.remove("b");

    DiskLruCache.Snapshot a = iterator.next();
    assertEquals("a", a.key());
    a.close();

    assertFalse(iterator.hasNext());
  }

  @Test public void iteratorRemove() throws Exception {
    set("a", "a1", "a2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    DiskLruCache.Snapshot a = iterator.next();
    a.close();
    iterator.remove();

    assertEquals(null, cache.get("a"));
  }

  @Test public void iteratorRemoveBeforeNext() throws Exception {
    set("a", "a1", "a2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();
    try {
      iterator.remove();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void iteratorRemoveOncePerCallToNext() throws Exception {
    set("a", "a1", "a2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    DiskLruCache.Snapshot a = iterator.next();
    iterator.remove();
    a.close();

    try {
      iterator.remove();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void cacheClosedTruncatesIterator() throws Exception {
    set("a", "a1", "a2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();
    cache.close();
    assertFalse(iterator.hasNext());
  }

  @Test public void isClosed_uninitializedCache() throws Exception {
    // Create an uninitialized cache.
    cache = new DiskLruCache(fileSystem, cacheDir, appVersion, 2, Integer.MAX_VALUE, executor);
    toClose.add(cache);

    assertFalse(cache.isClosed());
    cache.close();
    assertTrue(cache.isClosed());
  }

  @Test public void journalWriteFailsDuringEdit() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");

    // We can't begin the edit if writing 'DIRTY' fails.
    fileSystem.setFaultyWrite(journalFile, true);
    assertNull(cache.edit("c"));

    // Once the journal has a failure, subsequent writes aren't permitted.
    fileSystem.setFaultyWrite(journalFile, false);
    assertNull(cache.edit("d"));

    // Confirm that the fault didn't corrupt entries stored before the fault was introduced.
    cache.close();
    cache = new DiskLruCache(fileSystem, cacheDir, appVersion, 2, Integer.MAX_VALUE, executor);
    assertValue("a", "a", "a");
    assertValue("b", "b", "b");
    assertAbsent("c");
    assertAbsent("d");
  }

  /**
   * We had a bug where the cache was left in an inconsistent state after a journal write failed.
   * https://github.com/square/okhttp/issues/1211
   */
  @Test public void journalWriteFailsDuringEditorCommit() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");

    // Create an entry that fails to write to the journal during commit.
    DiskLruCache.Editor editor = cache.edit("c");
    setString(editor, 0, "c");
    setString(editor, 1, "c");
    fileSystem.setFaultyWrite(journalFile, true);
    editor.commit();

    // Once the journal has a failure, subsequent writes aren't permitted.
    fileSystem.setFaultyWrite(journalFile, false);
    assertNull(cache.edit("d"));

    // Confirm that the fault didn't corrupt entries stored before the fault was introduced.
    cache.close();
    cache = new DiskLruCache(fileSystem, cacheDir, appVersion, 2, Integer.MAX_VALUE, executor);
    assertValue("a", "a", "a");
    assertValue("b", "b", "b");
    assertAbsent("c");
    assertAbsent("d");
  }

  @Test public void journalWriteFailsDuringEditorAbort() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");

    // Create an entry that fails to write to the journal during abort.
    DiskLruCache.Editor editor = cache.edit("c");
    setString(editor, 0, "c");
    setString(editor, 1, "c");
    fileSystem.setFaultyWrite(journalFile, true);
    editor.abort();

    // Once the journal has a failure, subsequent writes aren't permitted.
    fileSystem.setFaultyWrite(journalFile, false);
    assertNull(cache.edit("d"));

    // Confirm that the fault didn't corrupt entries stored before the fault was introduced.
    cache.close();
    cache = new DiskLruCache(fileSystem, cacheDir, appVersion, 2, Integer.MAX_VALUE, executor);
    assertValue("a", "a", "a");
    assertValue("b", "b", "b");
    assertAbsent("c");
    assertAbsent("d");
  }

  @Test public void journalWriteFailsDuringRemove() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");

    // Remove, but the journal write will fail.
    fileSystem.setFaultyWrite(journalFile, true);
    assertTrue(cache.remove("a"));

    // Confirm that the entry was still removed.
    fileSystem.setFaultyWrite(journalFile, false);
    cache.close();
    cache = new DiskLruCache(fileSystem, cacheDir, appVersion, 2, Integer.MAX_VALUE, executor);
    assertAbsent("a");
    assertValue("b", "b", "b");
  }

  @Test public void cleanupTrimFailurePreventsNewEditors() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aa");
    set("b", "bb", "bbb");

    // Cause the cache trim job to fail.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), true);
    executor.jobs.pop().run();

    // Confirm that edits are prevented after a cache trim failure.
    assertNull(cache.edit("a"));
    assertNull(cache.edit("b"));
    assertNull(cache.edit("c"));

    // Allow the test to clean up.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), false);
  }

  @Test public void cleanupTrimFailureRetriedOnEditors() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aa");
    set("b", "bb", "bbb");

    // Cause the cache trim job to fail.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), true);
    executor.jobs.pop().run();

    // An edit should now add a job to clean up if the most recent trim failed.
    assertNull(cache.edit("b"));
    executor.jobs.pop().run();

    // Confirm a successful cache trim now allows edits.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), false);
    assertNull(cache.edit("c"));
    executor.jobs.pop().run();
    set("c", "cc", "cc");
    assertValue("c", "cc", "cc");
  }

  @Test public void cleanupTrimFailureWithInFlightEditor() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aaa");
    set("b", "bb", "bb");
    DiskLruCache.Editor inFlightEditor = cache.edit("c");

    // Cause the cache trim job to fail.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), true);
    executor.jobs.pop().run();

    // The in-flight editor can still write after a trim failure.
    setString(inFlightEditor, 0, "cc");
    setString(inFlightEditor, 1, "cc");
    inFlightEditor.commit();

    // Confirm the committed values are present after a successful cache trim.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), false);
    executor.jobs.pop().run();
    assertValue("c", "cc", "cc");
  }

  @Test public void cleanupTrimFailureAllowsSnapshotReads() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aa");
    set("b", "bb", "bbb");

    // Cause the cache trim job to fail.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), true);
    executor.jobs.pop().run();

    // Confirm we still allow snapshot reads after a trim failure.
    assertValue("a", "aa", "aa");
    assertValue("b", "bb", "bbb");

    // Allow the test to clean up.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), false);
  }

  @Test public void cleanupTrimFailurePreventsSnapshotWrites() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aa");
    set("b", "bb", "bbb");

    // Cause the cache trim job to fail.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), true);
    executor.jobs.pop().run();

    // Confirm snapshot writes are prevented after a trim failure.
    DiskLruCache.Snapshot snapshot1 = cache.get("a");
    assertNull(snapshot1.edit());
    snapshot1.close();
    DiskLruCache.Snapshot snapshot2 = cache.get("b");
    assertNull(snapshot2.edit());
    snapshot2.close();

    // Allow the test to clean up.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), false);
  }

  @Test public void evictAllAfterCleanupTrimFailure() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aa");
    set("b", "bb", "bbb");

    // Cause the cache trim job to fail.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), true);
    executor.jobs.pop().run();

    // Confirm we prevent edits after a trim failure.
    assertNull(cache.edit("c"));

    // A successful eviction should allow new writes.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), false);
    cache.evictAll();
    set("c", "cc", "cc");
    assertValue("c", "cc", "cc");
  }

  @Test public void manualRemovalAfterCleanupTrimFailure() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aa");
    set("b", "bb", "bbb");

    // Cause the cache trim job to fail.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), true);
    executor.jobs.pop().run();

    // Confirm we prevent edits after a trim failure.
    assertNull(cache.edit("c"));

    // A successful removal which trims the cache should allow new writes.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), false);
    cache.remove("a");
    set("c", "cc", "cc");
    assertValue("c", "cc", "cc");
  }

  @Test public void flushingAfterCleanupTrimFailure() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aa");
    set("b", "bb", "bbb");

    // Cause the cache trim job to fail.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), true);
    executor.jobs.pop().run();

    // Confirm we prevent edits after a trim failure.
    assertNull(cache.edit("c"));

    // A successful flush trims the cache and should allow new writes.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.0"), false);
    cache.flush();
    set("c", "cc", "cc");
    assertValue("c", "cc", "cc");
  }

  @Test public void cleanupTrimFailureWithPartialSnapshot() throws Exception {
    cache.setMaxSize(8);
    executor.jobs.pop();
    set("a", "aa", "aa");
    set("b", "bb", "bbb");

    // Cause the cache trim to fail on the second value leaving a partial snapshot.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.1"), true);
    executor.jobs.pop().run();

    // Confirm the partial snapshot is not returned.
    assertNull(cache.get("a"));

    // Confirm we prevent edits after a trim failure.
    assertNull(cache.edit("a"));

    // Confirm the partial snapshot is not returned after a successful trim.
    fileSystem.setFaultyDelete(new File(cacheDir, "a.1"), false);
    executor.jobs.pop().run();
    assertNull(cache.get("a"));
  }

  @Test public void noSizeCorruptionAfterCreatorDetached() throws Exception {
    // Create an editor for k1. Detach it by clearing the cache.
    DiskLruCache.Editor editor = cache.edit("k1");
    setString(editor, 0, "a");
    setString(editor, 1, "a");
    cache.evictAll();

    // Create a new value in its place.
    set("k1", "bb", "bb");
    assertEquals(4, cache.size());

    // Committing the detached editor should not change the cache's size.
    editor.commit();
    assertEquals(4, cache.size());
    assertValue("k1", "bb", "bb");
  }

  @Test public void noSizeCorruptionAfterEditorDetached() throws Exception {
    set("k1", "a", "a");

    // Create an editor for k1. Detach it by clearing the cache.
    DiskLruCache.Editor editor = cache.edit("k1");
    setString(editor, 0, "bb");
    setString(editor, 1, "bb");
    cache.evictAll();

    // Create a new value in its place.
    set("k1", "ccc", "ccc");
    assertEquals(6, cache.size());

    // Committing the detached editor should not change the cache's size.
    editor.commit();
    assertEquals(6, cache.size());
    assertValue("k1", "ccc", "ccc");
  }

  @Test public void noNewSourceAfterEditorDetached() throws Exception {
    set("k1", "a", "a");

    DiskLruCache.Editor editor = cache.edit("k1");
    cache.evictAll();

    assertNull(editor.newSource(0));
  }

  @Test public void editsDiscardedAfterEditorDetached() throws Exception {
    set("k1", "a", "a");

    // Create an editor, then detach it.
    DiskLruCache.Editor editor = cache.edit("k1");
    BufferedSink sink = Okio.buffer(editor.newSink(0));
    cache.evictAll();

    // Create another value in its place.
    set("k1", "ccc", "ccc");

    // Complete the original edit. It goes into a black hole.
    sink.writeUtf8("bb");
    sink.close();

    assertValue("k1", "ccc", "ccc");
  }

  @Test public void abortAfterDetach() throws Exception {
    set("k1", "a", "a");

    DiskLruCache.Editor editor = cache.edit("k1");
    cache.evictAll();

    editor.abort();
    assertEquals(0, cache.size());
    assertAbsent("k1");
  }

  private void assertJournalEquals(String... expectedBodyLines) throws Exception {
    List<String> expectedLines = new ArrayList<>();
    expectedLines.add(MAGIC);
    expectedLines.add(VERSION_1);
    expectedLines.add("100");
    expectedLines.add("2");
    expectedLines.add("");
    expectedLines.addAll(Arrays.asList(expectedBodyLines));
    assertEquals(expectedLines, readJournalLines());
  }

  private void createJournal(String... bodyLines) throws Exception {
    createJournalWithHeader(MAGIC, VERSION_1, "100", "2", "", bodyLines);
  }

  private void createJournalWithHeader(String magic, String version, String appVersion,
      String valueCount, String blank, String... bodyLines) throws Exception {
    BufferedSink sink = Okio.buffer(fileSystem.sink(journalFile));
    sink.writeUtf8(magic + "\n");
    sink.writeUtf8(version + "\n");
    sink.writeUtf8(appVersion + "\n");
    sink.writeUtf8(valueCount + "\n");
    sink.writeUtf8(blank + "\n");
    for (String line : bodyLines) {
      sink.writeUtf8(line);
      sink.writeUtf8("\n");
    }
    sink.close();
  }

  private List<String> readJournalLines() throws Exception {
    List<String> result = new ArrayList<>();
    BufferedSource source = Okio.buffer(fileSystem.source(journalFile));
    for (String line; (line = source.readUtf8Line()) != null; ) {
      result.add(line);
    }
    source.close();
    return result;
  }

  private File getCleanFile(String key, int index) {
    return new File(cacheDir, key + "." + index);
  }

  private File getDirtyFile(String key, int index) {
    return new File(cacheDir, key + "." + index + ".tmp");
  }

  private String readFile(File file) throws Exception {
    BufferedSource source = Okio.buffer(fileSystem.source(file));
    String result = source.readUtf8();
    source.close();
    return result;
  }

  public void writeFile(File file, String content) throws Exception {
    BufferedSink sink = Okio.buffer(fileSystem.sink(file));
    sink.writeUtf8(content);
    sink.close();
  }

  private static void assertInoperable(DiskLruCache.Editor editor) throws Exception {
    try {
      setString(editor, 0, "A");
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      editor.newSource(0);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      editor.newSink(0);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      editor.commit();
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      editor.abort();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  private void generateSomeGarbageFiles() throws Exception {
    File dir1 = new File(cacheDir, "dir1");
    File dir2 = new File(dir1, "dir2");
    writeFile(getCleanFile("g1", 0), "A");
    writeFile(getCleanFile("g1", 1), "B");
    writeFile(getCleanFile("g2", 0), "C");
    writeFile(getCleanFile("g2", 1), "D");
    writeFile(getCleanFile("g2", 1), "D");
    writeFile(new File(cacheDir, "otherFile0"), "E");
    writeFile(new File(dir2, "otherFile1"), "F");
  }

  private void assertGarbageFilesAllDeleted() {
    assertFalse(fileSystem.exists(getCleanFile("g1", 0)));
    assertFalse(fileSystem.exists(getCleanFile("g1", 1)));
    assertFalse(fileSystem.exists(getCleanFile("g2", 0)));
    assertFalse(fileSystem.exists(getCleanFile("g2", 1)));
    assertFalse(fileSystem.exists(new File(cacheDir, "otherFile0")));
    assertFalse(fileSystem.exists(new File(cacheDir, "dir1")));
  }

  private void set(String key, String value0, String value1) throws Exception {
    DiskLruCache.Editor editor = cache.edit(key);
    setString(editor, 0, value0);
    setString(editor, 1, value1);
    editor.commit();
  }

  public static void setString(DiskLruCache.Editor editor, int index, String value)
      throws IOException {
    BufferedSink writer = Okio.buffer(editor.newSink(index));
    writer.writeUtf8(value);
    writer.close();
  }

  private void assertAbsent(String key) throws Exception {
    DiskLruCache.Snapshot snapshot = cache.get(key);
    if (snapshot != null) {
      snapshot.close();
      fail();
    }
    assertFalse(fileSystem.exists(getCleanFile(key, 0)));
    assertFalse(fileSystem.exists(getCleanFile(key, 1)));
    assertFalse(fileSystem.exists(getDirtyFile(key, 0)));
    assertFalse(fileSystem.exists(getDirtyFile(key, 1)));
  }

  private void assertValue(String key, String value0, String value1) throws Exception {
    DiskLruCache.Snapshot snapshot = cache.get(key);
    assertSnapshotValue(snapshot, 0, value0);
    assertSnapshotValue(snapshot, 1, value1);
    assertTrue(fileSystem.exists(getCleanFile(key, 0)));
    assertTrue(fileSystem.exists(getCleanFile(key, 1)));
    snapshot.close();
  }

  private void assertSnapshotValue(DiskLruCache.Snapshot snapshot, int index, String value)
      throws IOException {
    assertEquals(value, sourceAsString(snapshot.getSource(index)));
    assertEquals(value.length(), snapshot.getLength(index));
  }

  private String sourceAsString(Source source) throws IOException {
    return source != null ? Okio.buffer(source).readUtf8() : null;
  }

  private void copyFile(File from, File to) throws IOException {
    Source source = fileSystem.source(from);
    BufferedSink sink = Okio.buffer(fileSystem.sink(to));
    sink.writeAll(source);
    source.close();
    sink.close();
  }

  private static class TestExecutor implements Executor {
    final Deque<Runnable> jobs = new ArrayDeque<>();

    @Override public void execute(Runnable command) {
      jobs.addLast(command);
    }
  }
}
