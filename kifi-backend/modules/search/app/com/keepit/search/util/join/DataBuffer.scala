package com.keepit.search.util.join

import scala.collection.mutable.ArrayBuffer
import java.lang.{ Float => JFloat }

object DataBuffer {
  type Page = Array[Short]

  private[join] val PAGE_SHORT_ARRAY_SIZE = 2048 // short array size
  val PAGE_SIZE = PAGE_SHORT_ARRAY_SIZE << 1

  // We encode the record type and the record size together into one short word (16-bit)
  // The first bit of the code is used as flag that indicates there is a record, so...
  val DESCRIPTOR_SIZE = 2
  val MAX_RECTYPEID = 0x7F // this needs to fit in 7-bit
  val MAX_DATASIZE = 500 // this (byte size) divided by two (-> the number of short words) needs to fits in 8-bit

  @inline def taggedFloatBits(tag: Byte, value: Float): Int = (tag & 0xff) | (JFloat.floatToRawIntBits(value) & 0xffffff00)
  @inline def getTaggedFloatTag(bits: Int): Byte = bits.toByte
  @inline def getTaggedFloatValue(bits: Int): Float = JFloat.intBitsToFloat(bits) // this doesn't clear the tag bits
  @inline def clearTag(value: Float): Float = JFloat.intBitsToFloat(JFloat.floatToRawIntBits(value) & 0xffffff00)

  class FloatTagger(tag: Int) {
    require(0 <= tag && tag <= Byte.MaxValue, s"tag value out of range : $tag")
    @inline def tagFloat(value: Float): Int = tag | (JFloat.floatToRawIntBits(value) & 0xffffff00)
  }
}

class DataBuffer(maxPages: Int = 10000) {
  // this class is not thread-safe

  import com.keepit.search.util.join.DataBuffer._

  private[this] var _numPages = 0
  private[this] var _numRecords = 0
  private[this] var _localOffset = 0 // bytes
  private[this] var _freeSpace = 0 // bytes
  private[this] var _currentPage: Page = null
  private[this] val _dataBuf = new ArrayBuffer[Page]()

  private[this] def addPage(): Unit = {
    _numPages += 1
    if (_numPages > maxPages) throw new DataBufferFullException("number of page exceeded the limit")

    _currentPage = new Page(DataBuffer.PAGE_SHORT_ARRAY_SIZE)
    _dataBuf += _currentPage
    _freeSpace = DataBuffer.PAGE_SIZE
    _localOffset = 0
  }

  def alloc(writer: DataBufferWriter, recType: Int, byteSize: Int): DataBufferWriter = {
    if (byteSize >= MAX_DATASIZE) throw new DataBufferException(s"data size too big: $byteSize")
    if (recType >= MAX_RECTYPEID) throw new DataBufferException(s"record type id too big: $recType")

    // add a page if not enough room
    if (_freeSpace - byteSize - DESCRIPTOR_SIZE < 0) addPage()

    val total = writer.set(recType, byteSize, _currentPage, _localOffset)
    _freeSpace -= total
    _localOffset += total
    _numRecords += 1
    writer
  }

  def set(reader: DataBufferReader, globalOffset: Int): DataBufferReader = {
    val page = _dataBuf(globalOffset / DataBuffer.PAGE_SIZE)
    val byteOffset = (globalOffset % DataBuffer.PAGE_SIZE)
    reader.set(globalOffset, page, byteOffset)
    reader
  }

  def scan[T](reader: DataBufferReader)(f: DataBufferReader => T): Unit = {
    _dataBuf.foldLeft(0) { (pageGlobalOffset, page) =>
      var nextByteOffset = 0
      while (nextByteOffset < DataBuffer.PAGE_SIZE && reader.set(pageGlobalOffset + nextByteOffset, page, nextByteOffset)) {
        nextByteOffset = reader.next
        f(reader)
      }
      pageGlobalOffset + DataBuffer.PAGE_SIZE
    }
  }

  // number of records
  def size: Int = _numRecords

  // number of pages
  def numPages: Int = _dataBuf.size
}

class DataBufferException(msg: String) extends Exception(msg)
class DataBufferFullException(msg: String) extends Exception(msg)
