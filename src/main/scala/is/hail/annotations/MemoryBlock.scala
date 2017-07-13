package is.hail.annotations

import java.util

import org.apache.spark.unsafe.Platform

final class MemoryBlock(val mem: Array[Long]) {
  require(mem.length < (Integer.MAX_VALUE / 8), "too big")

  def sizeInBytes: Int = mem.length * 8

  def loadInt(off: Int): Int = {
    assert(off + 4 <= sizeInBytes, s"tried to read int from offset $off with array size $sizeInBytes")
    Platform.getInt(mem, Platform.LONG_ARRAY_OFFSET + off)
  }

  def loadLong(off: Int): Long = {
    assert(off + 8 <= sizeInBytes, s"tried to read long from offset $off with array size $sizeInBytes")
    Platform.getLong(mem, Platform.LONG_ARRAY_OFFSET + off)
  }

  def loadFloat(off: Int): Float = {
    assert(off + 4 <= sizeInBytes, s"tried to read float from offset $off with array size $sizeInBytes")
    Platform.getFloat(mem, Platform.LONG_ARRAY_OFFSET + off)
  }

  def loadDouble(off: Int): Double = {
    assert(off + 8 <= sizeInBytes, s"tried to read double from offset $off with array size $sizeInBytes")
    Platform.getDouble(mem, Platform.LONG_ARRAY_OFFSET + off)
  }

  def loadByte(off: Int): Byte = {
    assert(off + 1 <= sizeInBytes, s"tried to read byte from offset $off with array size $sizeInBytes")
    Platform.getByte(mem, Platform.LONG_ARRAY_OFFSET + off)
  }

  def loadBytes(off: Int, size: Int): Array[Byte] = {
    assert(off + size <= sizeInBytes, s"tried to read bytes of size $size from offset $off with array size $sizeInBytes")
    val a = new Array[Byte](size)
    Platform.copyMemory(mem, Platform.LONG_ARRAY_OFFSET + off, a, Platform.BYTE_ARRAY_OFFSET, size)
    a
  }

  def storeInt(off: Int, i: Int) {
    assert(off + 4 <= sizeInBytes, s"tried to store int to offset $off with array size $sizeInBytes")
    Platform.putInt(mem, Platform.LONG_ARRAY_OFFSET + off, i)
  }

  def storeLong(off: Int, l: Long) {
    assert(off + 8 <= sizeInBytes, s"tried to store long to offset $off with array size $sizeInBytes")
    Platform.putLong(mem, Platform.LONG_ARRAY_OFFSET + off, l)
  }

  def storeFloat(off: Int, f: Float) {
    assert(off + 4 <= sizeInBytes, s"tried to store float to offset $off with array size $sizeInBytes")
    Platform.putFloat(mem, Platform.LONG_ARRAY_OFFSET + off, f)
  }

  def storeDouble(off: Int, d: Double) {
    assert(off + 8 <= sizeInBytes, s"tried to store double to offset $off with array size $sizeInBytes")
    Platform.putDouble(mem, Platform.LONG_ARRAY_OFFSET + off, d)
  }

  def storeByte(off: Int, b: Byte) {
    assert(off + 1 <= sizeInBytes, s"tried to store byte to offset $off with array size $sizeInBytes")
    Platform.putByte(mem, Platform.LONG_ARRAY_OFFSET + off, b)
  }

  def storeBytes(off: Int, bytes: Array[Byte]) {
    assert(off + bytes.length <= sizeInBytes, s"tried to store ${ bytes.length } bytes to offset $off with array size $sizeInBytes")
    Platform.copyMemory(bytes, Platform.BYTE_ARRAY_OFFSET, mem, Platform.LONG_ARRAY_OFFSET + off, bytes.length)
  }

  def reallocate(size: Int): MemoryBlock = {
    if (sizeInBytes < size) {
      val newMem = new Array[Long](math.max(mem.length * 2, (size + 7) / 8))
      Platform.copyMemory(mem, Platform.LONG_ARRAY_OFFSET, newMem, Platform.LONG_ARRAY_OFFSET, sizeInBytes)
      new MemoryBlock(newMem)
    } else
      this
  }

  def copy(): MemoryBlock = new MemoryBlock(util.Arrays.copyOf(mem, mem.length))
}

final class Pointer(val mem: MemoryBlock, val memOffset: Int) {

  def loadInt(): Int = mem.loadInt(memOffset)

  def loadInt(off: Int): Int = mem.loadInt(off + memOffset)

  def loadLong(): Int = mem.loadInt(memOffset)

  def loadLong(off: Int): Long = mem.loadLong(off + memOffset)

  def loadFloat(): Float = mem.loadFloat(memOffset)

  def loadFloat(off: Int): Float = mem.loadFloat(off + memOffset)

  def loadDouble(): Double = mem.loadDouble(memOffset)

  def loadDouble(off: Int): Double = mem.loadDouble(off + memOffset)

  def loadByte(): Byte = mem.loadByte(memOffset)

  def loadByte(off: Int): Byte = mem.loadByte(off + memOffset)

  def loadBytes(size: Int): Array[Byte] = mem.loadBytes(memOffset, size)

  def loadBytes(off: Int, size: Int): Array[Byte] = mem.loadBytes(off + memOffset, size)

  def storeInt(i: Int): Unit = mem.storeInt(memOffset, i)

  def storeInt(off: Int, i: Int): Unit = mem.storeInt(memOffset + off, i)

  def storeLong(l: Long): Unit = mem.storeLong(memOffset, l)

  def storeLong(off: Int, l: Long): Unit = mem.storeLong(memOffset + off, l)

  def storeFloat(f: Float): Unit = mem.storeFloat(memOffset, f)

  def storeFloat(off: Int, f: Float): Unit = mem.storeFloat(memOffset + off, f)

  def storeDouble(d: Double): Unit = mem.storeDouble(memOffset, d)

  def storeDouble(off: Int, d: Double): Unit = mem.storeDouble(memOffset + off, d)

  def storeByte(b: Byte): Unit = mem.storeByte(memOffset, b)

  def storeByte(off: Int, b: Byte): Unit = mem.storeByte(memOffset + off, b)

  def storeBytes(bytes: Array[Byte]): Unit = mem.storeBytes(memOffset, bytes)

  def storeBytes(off: Int, bytes: Array[Byte]): Unit = mem.storeBytes(memOffset + off, bytes)

  def offset(off: Int): Pointer = new Pointer(mem, memOffset + off)

  def copy(): Pointer = new Pointer(mem.copy(), memOffset)
}

final class MemoryBuffer(sizeHint: Int = 128) {
  var mb = new MemoryBlock(new Array[Long]((sizeHint + 7) / 8))

  var offset: Int = 0

  def alignAndEnsure(size: Int) {
    align(size)
    ensure(size)
  }

  def ensure(size: Int) {
    mb = mb.reallocate(offset + size)
  }

  def loadInt(off: Int): Int = mb.loadInt(off)

  def loadLong(off: Int): Long = mb.loadLong(off)

  def loadFloat(off: Int): Float = mb.loadFloat(off)

  def loadDouble(off: Int): Double = mb.loadDouble(off)

  def loadByte(off: Int): Byte = mb.loadByte(off)

  def loadBytes(off: Int, size: Int): Array[Byte] = mb.loadBytes(off, size)

  def storeInt(off: Int, i: Int): Unit = mb.storeInt(off, i)

  def storeLong(off: Int, l: Long): Unit = mb.storeLong(off, l)

  def storeFloat(off: Int, f: Float): Unit = mb.storeFloat(off, f)

  def storeDouble(off: Int, d: Double): Unit = mb.storeDouble(off, d)

  def storeByte(off: Int, b: Byte): Unit = mb.storeByte(off, b)

  def storeBytes(off: Int, bytes: Array[Byte]): Unit = mb.storeBytes(off, bytes)

  def appendInt(i: Int) {
    alignAndEnsure(4)
    mb.storeInt(offset, i)
    offset += 4
  }

  def appendLong(l: Long) {
    alignAndEnsure(8)
    mb.storeLong(offset, l)
    offset += 8
  }

  def appendFloat(f: Float) {
    alignAndEnsure(4)
    mb.storeFloat(offset, f)
    offset += 4
  }

  def appendDouble(d: Double) {
    alignAndEnsure(8)
    mb.storeDouble(offset, d)
    offset += 8
  }

  def appendByte(b: Byte) {
    ensure(1)
    mb.storeByte(offset, b)
    offset += 1
  }

  def appendBytes(bytes: Array[Byte]) {
    ensure(bytes.length)
    mb.storeBytes(offset, bytes)
    offset += bytes.length
  }

  def allocate(nBytes: Int): Int = {
    val currentOffset = offset
    ensure(nBytes)
    offset += nBytes
    currentOffset
  }

  def align(alignment: Int) {
    assert(alignment > 0)
    assert((alignment & (alignment - 1)) == 0) // power of 2
    offset = (offset + (alignment - 1)) & ~(alignment - 1)
  }

  def clear() {
    offset = 0
  }

  def result(): MemoryBlock = {
    val reqLength = (offset + 7) / 8
    val arr = new Array[Long](reqLength)
    Platform.copyMemory(mb.mem, Platform.LONG_ARRAY_OFFSET, arr, Platform.LONG_ARRAY_OFFSET, offset)
    new MemoryBlock(arr)
  }
}
