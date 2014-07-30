package com.keepit.search.util.join

class DataBufferWriter {
  import DataBuffer.Page

  private[this] var _type: Int = 0
  private[this] var _page: Page = null
  private[this] var _offset: Int = 0 // offset within the current page in terms of short words
  private[this] var _endoff: Int = 0 // end offset of the allocation in terms of short words
  private[this] var _current: Int = 0 // offset within the current page in terms of short words

  private[join] def set(recType: Int, byteSize: Int, page: Page, byteOffset: Int): Int = {
    // bit 0: record flag (0: no record, 1: there is a record)
    // bit 1-7: record type id
    // bit 9-15: size in short words
    val size = byteSize >> 1
    val offset = byteOffset >> 1
    page(offset) = (0x8000 | (recType << 8) | (size & 0xff)).toShort
    _type = recType
    _page = page
    _offset = offset + 1 // beginning of the data
    _endoff = offset + 1 + size // end of the allocation
    _current = offset + 1

    byteSize + DataBuffer.DESCRIPTOR_SIZE // total allocated
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
    putTaggedFloatBits(DataBuffer.taggedFloatBits(tag, value))
  }

  def putTaggedFloatBits(bits: Int): DataBufferWriter = {
    if (_current + 2 > _endoff) throw new DataBufferException("buffer overrun")

    _page(_current) = (bits >>> 16).toShort
    _page(_current + 1) = bits.toShort
    _current += 2
    this
  }
}
