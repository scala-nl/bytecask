/*
* Copyright 2011 P.Budzik
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* User: przemek
* Date: 1/6/12
* Time: 5:58 PM
*/

package bytecask

import java.util.zip.CRC32
import java.util.concurrent.atomic.AtomicInteger
import java.io._

import bytecask.Utils._
import java.nio.ByteBuffer
import bytecask.Files.boostedReader

object IO extends Logging {
  val HEADER_SIZE = 14 //crc, ts, ks, vs -> 4 + 4 + 2 + 4 bytes
  val DEFAULT_MAX_FILE_SIZE = Int.MaxValue // 2GB
  val ACTIVE_FILE_NAME = "0"
  val FILE_REGEX = "^[0-9]+$"

  def appendEntry(appender: RandomAccessFile, key: Bytes, value: Bytes) = appender.synchronized {
    val pos = appender.getFilePointer
    val timestamp = (Utils.now / 1000).intValue()
    val keySize = key.size
    val valueSize = value.size
    val length = IO.HEADER_SIZE + keySize + valueSize
    val buffer = ByteBuffer.allocate(length)
    putInt32(buffer, timestamp, 4)
    putInt16(buffer, keySize, 8)
    putInt32(buffer, valueSize, 10)
    buffer.position(buffer.position() + 14)
    buffer.put(key)
    buffer.put(value)
    val crc = new CRC32
    crc.update(buffer.array(), 4, length - 4)
    putInt32(buffer, crc.getValue, 0)
    buffer.flip()
    appender.getChannel.write(buffer)
    (pos.toInt, length, timestamp)
  }

  /*
 Indexed read
  */

  def readEntry(reader: RandomAccessFile, entry: IndexEntry) = {
    reader.getChannel.position(entry.pos)
    val buffer = ByteBuffer.allocate(entry.length)
    val read = reader.getChannel.read(buffer)
    buffer.flip()
    if (read < entry.length) throw new IOException("Could not read all data: %s/%s".format(read, entry.length))
    val expectedCrc = readUInt32(buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3))
    val crc = new CRC32
    val a = buffer.array()
    crc.update(a, 4, entry.length - 4)
    val actualCrc = crc.getValue.toInt
    if (expectedCrc != actualCrc) throw new IOException("CRC check failed: %s != %s".format(expectedCrc, actualCrc))
    val timestamp = readUInt32(buffer.get(4), buffer.get(5), buffer.get(6), buffer.get(7))
    val keySize = readUInt16(buffer.get(8), buffer.get(9))
    val valueSize = readUInt32(buffer.get(10), buffer.get(11), buffer.get(12), buffer.get(13))
    val key = new Array[Byte](keySize)
    Array.copy(a, IO.HEADER_SIZE, key, 0, keySize)
    val value = new Array[Byte](valueSize)
    Array.copy(a, IO.HEADER_SIZE + keySize, value, 0, valueSize)
    FileEntry(entry.pos, actualCrc, keySize, valueSize, timestamp, key, value)
  }

  /*
 Iterative non-indexed read
  */

  def readEntry(reader: RandomAccessFile) = {
    val pos = reader.getFilePointer
    val header = new Array[Byte](IO.HEADER_SIZE)
    reader.readOrThrow(header, "Failed to read chunk of %s bytes".format(IO.HEADER_SIZE))
    val expectedCrc = readUInt32(header(0), header(1), header(2), header(3))
    val timestamp = readUInt32(header(4), header(5), header(6), header(7))
    val keySize = readUInt16(header(8), header(9))
    val valueSize = readUInt32(header(10), header(11), header(12), header(13))
    val key = new Array[Byte](keySize)
    reader.readOrThrow(key, "Failed to read chunk of %s bytes".format(keySize))
    val value = new Array[Byte](valueSize)
    reader.readOrThrow(value, "Failed to read chunk of %s bytes".format(valueSize))
    val crc = new CRC32
    crc.update(header, 4, 10)
    crc.update(key, 0, keySize)
    crc.update(value, 0, valueSize)
    val actualCrc = crc.getValue.toInt
    if (expectedCrc != actualCrc) throw new IOException("CRC check failed: %s != %s".format(expectedCrc, actualCrc))
    FileEntry(pos.toInt, actualCrc, keySize, valueSize, timestamp, key, value)
  }

  @inline
  def readEntry(pool: RandomAccessFilePool, dir: String, entry: IndexEntry): FileEntry = {
    withPooled(pool, dir + "/" + entry.file) {
      reader => readEntry(reader, entry)
    }
  }

  def readEntries(file: File, callback: (File, FileEntry) => Any): Boolean = {
    val length = file.length()
    val reader = new RandomAccessFile(file, "r")
    try {
      while (reader.getFilePointer < length) {
        val entry = readEntry(reader)
        callback(file, entry)
      }
      true
    } catch {
      case e: IOException =>
        warn(e.toString)
        false
    } finally {
      reader.close()
    }
  }

  private def readAll(file: File, reader: RandomAccessFile, callback: (File, FileEntry) => Any) {
    val entry = readEntry(reader)
    callback(file, entry)
    readAll(file, reader, callback)
  }

  @inline
  private def readUInt32(a: Byte, b: Byte, c: Byte, d: Byte) = {
    (a & 0xFF) << 24 | (b & 0xFF) << 16 | (c & 0xFF) << 8 | (d & 0xFF) << 0
  }

  @inline
  private def readUInt16(a: Byte, b: Byte) = (a & 0xFF) << 8 | (b & 0xFF) << 0

  @inline
  private def putInt32(buffer: ByteBuffer, value: Int, index: Int = 0) {
    buffer.put(index, (value >>> 24).toByte)
    buffer.put(index + 1, (value >>> 16).toByte)
    buffer.put(index + 2, (value >>> 8).toByte)
    buffer.put(index + 3, value.byteValue)
  }

  @inline
  private def putInt32(buffer: ByteBuffer, value: Long, index: Int) {
    buffer.put(index, (value >>> 24).toByte)
    buffer.put(index + 1, (value >>> 16).toByte)
    buffer.put(index + 2, (value >>> 8).toByte)
    buffer.put(index + 3, value.toByte)
  }

  @inline
  private def putInt16(buffer: ByteBuffer, value: Int, index: Int = 0) {
    buffer.put(index, (value >>> 8).toByte)
    buffer.put(index + 1, value.toByte)
  }
}

final class IO(val dir: String, maxConcurrentReaders: Int = 10) extends Closeable with Logging with Locking {
  val activeFile = dir + "/" + IO.ACTIVE_FILE_NAME
  val splits = new AtomicInteger
  var appender = createAppender()
  lazy val readers = new RandomAccessFilePool(maxConcurrentReaders)

  def appendEntry(key: Bytes, value: Bytes) = {
    IO.appendEntry(appender, key, value)
  }

  def readValue(entry: IndexEntry): Array[Byte] = {
    IO.readEntry(readers, dir, entry).value
  }

  private def createAppender() = writeLock {
    new RandomAccessFile(activeFile, "rw")
  }

  def split() = {
    //debug("Splitting...")
    appender.close()
    val next = nextFile()
    activeFile.mkFile.renameTo(next)
    appender = createAppender()
    splits.incrementAndGet()
    next.getName
  }

  /*
  Next file that should be created to move current/active file to
   */

  private def nextFile() = {
    val files = ls(dir).filter(f => f.isFile && f.getName.matches(IO.FILE_REGEX)).map(_.getName.toInt).sortWith(_ < _)
    val slot = firstSlot(files)
    val next = if (!slot.isEmpty) slot.get else (files.last + 1)
    (dir / next).mkFile
  }

  def close() {
    readers.destroy()
    appender.close()
  }

  def pos = appender.getFilePointer

  /*
 Deletes, but also makes sure whenever a file is deleted we don't keep cached file objects
  */

  def delete(file: String): Boolean = delete(file.mkFile)

  def delete(file: File): Boolean = {
    if (file.delete()) {
      readers.invalidate(file.getAbsolutePath)
      true
    } else false
  }
}

final case class FileEntry(pos: Int, crc: Int, keySize: Int, valueSize: Int, timestamp: Int, key: Array[Byte], value: Array[Byte]) {
  def size = IO.HEADER_SIZE + key.length + value.length
}
