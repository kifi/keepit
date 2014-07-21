package com.keepit.search.util.join

class DataBufferWriter {
  import DataBuffer.Page

  private[this] var _type: Int = 0
  private[this] var _page: Page = null
  private[this] var _offset: Int = 0 // offset within the current page in terms of short
  private[this] var _endoff: Int = 0 // end offset of the allocation
  private[this] var _current: Int = 0 // offset within the current page in terms of short

  private[util] def set(recType: Int, size: Int, page: Page, offset: Int): Boolean = {
    page(offset) = (0x8000 | (recType << 8) | (size & 0xff)).toShort
    _type = recType
    _page = page
    _offset = offset + 1 // beginning of the data
    _endoff = offset + 1 + size // end of the allocation
    _current = offset + 1
    true
  }

  // Long
  def putLong(value: Long): DataBufferWriter = {
    if (_current + 4 > _endoff) throw new DataBufferException("buffer overrun")

    _page(_current) = (value >>> 48).toShort
    _page(_current + 1) = (value >>> 32).toShort
    _page(_current + 2) = (value >>> 16).toShort
    _page(_current + 3) = value.toShort
    _current += 4
    this
  }

  // Int
  def putInt(value: Int): DataBufferWriter = {
    if (_current + 2 > _endoff) throw new DataBufferException("buffer overrun")

    _page(_current) = (value >>> 16).toShort
    _page(_current + 1) = value.toShort
    _current += 2
    this
  }

  // Short
  def putShort(value: Short): DataBufferWriter = {
    if (_current + 1 > _endoff) throw new DataBufferException("buffer overrun")

    _page(_current) = value
    _current += 1
    this
  }

  // Float
  def putFloat(value: Float): DataBufferWriter = {
    if (_current + 2 > _endoff) throw new DataBufferException("buffer overrun")

    val bits = java.lang.Float.floatToRawIntBits(value)
    _page(_current) = (bits >>> 16).toShort
    _page(_current + 1) = bits.toShort
    _current += 2
    this
  }

  // Tagged Float
  def putTaggedFloat(tag: Byte, value: Float): DataBufferWriter = {
    if (_current + 2 > _endoff) throw new DataBufferException("buffer overrun")

    val bits = ((tag & 0xff) << 24) | (java.lang.Float.floatToRawIntBits(value) >>> 8)
    _page(_current) = (bits >>> 16).toShort
    _page(_current + 1) = bits.toShort
    _current += 2
    this
  }
}
