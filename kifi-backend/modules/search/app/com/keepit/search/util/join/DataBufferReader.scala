package com.keepit.search.util.join

class DataBufferReader {
  import DataBuffer.Page

  private[this] var _type: Int = 0
  private[this] var _size: Int = 0 // record size in terms of short words
  private[this] var _page: Page = null
  private[this] var _globalOffset: Int = 0 // global byte offset
  private[this] var _offset: Int = 0 // offset within the current page in terms of short words
  private[this] var _current: Int = 0 // offset within the current page in terms of short words

  private[join] def set(globalOffset: Int, page: Page, byteOffset: Int): Boolean = {
    _globalOffset = globalOffset
    val offset = byteOffset >> 1
    val tag = page(offset)

    if ((tag & 0x8000) != 0) { // validity check
      _type = (tag & 0x7fff) >>> 8
      _size = (tag & 0xff)
      _page = page
      _offset = offset + 1 // beginning of the data
      _current = offset + 1
      true
    } else {
      false
    }
  }

  private[util] def next: Int = (_offset + _size) << 1 // byte offset of next record

  def recordType: Int = _type // record type

  def recordOffset: Int = _globalOffset // global byte offset

  def hasMore(): Boolean = _current < _offset + _size

  // Long
  def nextLong(): Long = {
    var ret = _page(_current) & 0xffffL
    ret = ret << 16 | (_page(_current + 1) & 0xffffL)
    ret = ret << 16 | (_page(_current + 2) & 0xffffL)
    ret = ret << 16 | (_page(_current + 3) & 0xffffL)
    _current += 4
    ret
  }

  def skipLong(): Unit = { _current += 4 }

  def getLong(offset: Int): Long = {
    val off = _offset + (offset >>> 1)
    var ret = _page(off) & 0xffffL
    ret = ret << 16 | (_page(off + 1) & 0xffffL)
    ret = ret << 16 | (_page(off + 2) & 0xffffL)
    ret = ret << 16 | (_page(off + 3) & 0xffffL)
    ret
  }

  // Int
  def nextInt(): Int = {
    var ret = _page(_current) & 0xffff
    ret = ret << 16 | (_page(_current + 1) & 0xffff)
    _current += 2
    ret
  }

  def skipInt(): Unit = { _current += 2 }

  def getInt(offset: Int): Int = {
    val off = _offset + (offset >>> 1)
    var ret = _page(off) & 0xffff
    ret = ret << 16 | (_page(off + 1) & 0xffff)
    ret
  }

  // Short
  def nextShort(): Short = {
    var ret = _page(_current)
    _current += 1
    ret
  }

  def skipShort(): Unit = { _current += 1 }

  def getShort(offset: Int): Short = {
    val off = _offset + (offset >>> 1)
    var ret = _page(off)
    ret
  }

  // Float
  def nextFloat(): Float = {
    var bits = _page(_current) & 0xffff
    bits = bits << 16 | (_page(_current + 1) & 0xffff)
    _current += 2
    java.lang.Float.intBitsToFloat(bits)
  }

  def skipFloat(): Unit = { _current += 2 }

  def getFloat(offset: Int): Float = {
    val off = _offset + (offset >>> 1)
    val bits = ((_page(off) & 0xffff) << 16) | (_page(off + 1) & 0xffff)
    java.lang.Float.intBitsToFloat(bits)
  }

  // Tagged Float Bits (Int), use DataBuffer.getTaggedFloatTag and DataBuffer.getTaggedFloatValue to decode
  def nextTaggedFloatBits(): Int = {
    var ret = _page(_current) & 0xffff
    ret = ret << 16 | (_page(_current + 1) & 0xffff)
    _current += 2
    ret
  }
  // Tagged Float Tag
  def peekTaggedFloatTag(): Byte = {
    (_page(_current) >>> 8).toByte
  }
  // Tagged Float Value
  def nextTaggedFloatValue(): Float = {
    DataBuffer.getTaggedFloatValue(nextTaggedFloatBits())
  }

  def skipTaggedFloat(): Unit = { _current += 2 }

  // Tagged Float Bits (Int), use DataBuffer.getTaggedFloatTag and DataBuffer.getTaggedFloatValue to decode
  def getTaggedFloatBits(offset: Int): Int = {
    val off = _offset + (offset >>> 1)
    var ret = _page(off) & 0xffff
    ret = ret << 16 | (_page(off + 1) & 0xffff)
    ret
  }
  def getTaggedFloatTag(offset: Int): Byte = {
    val off = _offset + (offset >>> 1)
    (_page(_offset) >>> 8).toByte
  }
  def getTaggedFloatValue(offset: Int): Float = {
    DataBuffer.getTaggedFloatValue(getTaggedFloatBits(offset))
  }
}
