package com.keepit.search.util.join

import scala.collection.mutable.ArrayBuffer

object DataBuffer {
  type Page = Array[Short]

  private[join] val PAGE_SHORT_ARRAY_SIZE = 1024 // short array size
  val PAGE_SIZE = PAGE_SHORT_ARRAY_SIZE << 1

  // We encode the record type and the record size together into one short word (16-bit)
  // The first bit of the code is used as flag that indicates there is a record, so...
  val DESCRIPTOR_SIZE = 2
  val MAX_RECTYPEID = 100 // this needs to fit in 7-bit
  val MAX_DATASIZE = 500 // this (byte size) divided by two (-> the number of short words) needs to fits in 8-bit

  @inline def taggedFloatBits(tag: Byte, value: Float): Int = ((tag & 0xff) << 24) | (java.lang.Float.floatToRawIntBits(value) >>> 8)
}

class DataBuffer {
  // this class is not thread-safe

  import com.keepit.search.util.join.DataBuffer._

  private[this] var _numRecords = 0
  private[this] var _localOffset = 0 // bytes
  private[this] var _freeSpace = 0 // bytes
  private[this] var _currentPage: Page = null
  private[this] val _dataBuf = new ArrayBuffer[Page]()

  private[this] def addPage(): Unit = {
    _currentPage = new Page(DataBuffer.PAGE_SHORT_ARRAY_SIZE)
    _dataBuf += _currentPage
    _freeSpace = DataBuffer.PAGE_SIZE
    _localOffset = 0
  }

  def alloc(writer: DataBufferWriter, recType: Int, byteSize: Int): DataBufferWriter = {
    if (byteSize >= MAX_DATASIZE) throw new DataBufferException(s"data size too big: $byteSize")
    if (recType >= MAX_RECTYPEID) throw new DataBufferException(s"record type id too big: $recType")
    if (byteSize % 2 != 0) throw new DataBufferException(s"data size not aligned")

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
    var pageGlobalOffset = 0
    _dataBuf.foreach { page =>
      var byteOffset = 0
      while (byteOffset < DataBuffer.PAGE_SIZE && reader.set(pageGlobalOffset + byteOffset, page, byteOffset)) {
        val next = reader.next
        f(reader)
        byteOffset = next
      }
      pageGlobalOffset += DataBuffer.PAGE_SIZE
    }
  }

  // number of records
  def size: Int = _numRecords

  // number of pages
  def numPages: Int = _dataBuf.size
}

class DataBufferException(msg: String) extends Exception(msg)
