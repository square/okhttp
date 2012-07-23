/*
 * Copyright (C) 2010 The Android Open Source Project
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

package libcore.io;

/**
 * Iterates over big- or little-endian bytes. See {@link MemoryMappedFile#bigEndianIterator} and
 * {@link MemoryMappedFile#littleEndianIterator}.
 *
 * @hide don't make this public without adding bounds checking.
 */
public abstract class BufferIterator {
    /**
     * Seeks to the absolute position {@code offset}, measured in bytes from the start.
     */
    public abstract void seek(int offset);

    /**
     * Skips forwards or backwards {@code byteCount} bytes from the current position.
     */
    public abstract void skip(int byteCount);

    /**
     * Copies {@code byteCount} bytes from the current position into {@code dst}, starting at
     * {@code dstOffset}, and advances the current position {@code byteCount} bytes.
     */
    public abstract void readByteArray(byte[] dst, int dstOffset, int byteCount);

    /**
     * Returns the byte at the current position, and advances the current position one byte.
     */
    public abstract byte readByte();

    /**
     * Returns the 32-bit int at the current position, and advances the current position four bytes.
     */
    public abstract int readInt();

    /**
     * Copies {@code intCount} 32-bit ints from the current position into {@code dst}, starting at
     * {@code dstOffset}, and advances the current position {@code 4 * intCount} bytes.
     */
    public abstract void readIntArray(int[] dst, int dstOffset, int intCount);

    /**
     * Returns the 16-bit short at the current position, and advances the current position two bytes.
     */
    public abstract short readShort();
}
