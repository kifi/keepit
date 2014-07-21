package com.keepit.search.util.join

import scala.collection.mutable.ArrayBuffer

object DataBuffer {
  type Page = Array[Short]

  val PAGE_SIZE = 1024 // 2KBytes
  val MAX_RECTYPEID = 100
  val MAX_DATASIZE = 500
}

class DataBuffer {
  // this class is not thread-safe

  import com.keepit.search.util.join.DataBuffer._

  private[this] var _localOffset = 0
  private[this] var _numRecords = 0
  private[this] var _freeSpace = 0
  private[this] var _currentPage: Page = null
  private[this] val _dataBuf = new ArrayBuffer[Page]()

  private[this] def addPage(): Unit = {
    _currentPage = new Page(DataBuffer.PAGE_SIZE)
    _dataBuf += _currentPage
    _freeSpace = DataBuffer.PAGE_SIZE
    _localOffset = 0
  }

  def alloc(writer: DataBufferWriter, recType: Int, byteSize: Int): DataBufferWriter = {
    if (byteSize >= MAX_DATASIZE) throw new DataBufferException(s"data size too big: $byteSize")
    if (recType >= MAX_RECTYPEID) throw new DataBufferException(s"record type id too big: $recType")

    val size = ((byteSize + 1) / 2).toByte

    // add a page if not enough room
    if (_freeSpace - size - 1 < 0) addPage()

    writer.set(recType, size, _currentPage, _localOffset)
    _freeSpace -= (size + 1)
    _localOffset += (size + 1)
    _numRecords += 1
    writer
  }

  def set(reader: DataBufferReader, offset: Int): DataBufferReader = {
    val page = _dataBuf(offset / DataBuffer.PAGE_SIZE)
    val off = offset % DataBuffer.PAGE_SIZE
    reader.set(offset, page, off)
    reader
  }

  def scan[T](reader: DataBufferReader)(f: DataBufferReader => T): Unit = {
    var pageOffset = 0
    _dataBuf.foreach { page =>
      var offset = 0
      while (offset < DataBuffer.PAGE_SIZE && reader.set(pageOffset + offset, page, offset)) {
        val next = reader.next
        f(reader)
        offset = next
      }
      pageOffset += DataBuffer.PAGE_SIZE
    }
  }

  // number of records
  def size: Int = _numRecords
}

class DataBufferException(msg: String) extends Exception(msg)

