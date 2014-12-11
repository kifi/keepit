package com.keepit.search.index.graph

import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object Util {
  private[this] val TIME_UNIT = 1000L * 60L // minute
  private[this] val UNIT_PER_HOUR = (1000L * 60L * 60L).toDouble / TIME_UNIT.toDouble

  def millisToUnit(millis: Long) = millis / TIME_UNIT
  def unitToMillis(units: Long) = units * TIME_UNIT

  def readList(in: InputStreamDataInput, length: Int): Array[Long] = {
    readList(in, new Array[Long](length), 0, length)
  }

  def readList(in: InputStreamDataInput, arr: Array[Long], offset: Int, length: Int): Array[Long] = {
    var current = 0L;
    var i = offset
    val end = offset + length
    while (i < end) {
      val id = current + in.readVLong
      arr(i) = id
      current = id
      i += 1
    }
    arr
  }

  def readRawList(in: InputStreamDataInput, length: Int): Array[Long] = {
    readRawList(in, new Array[Long](length), 0, length)
  }

  def readRawList(in: InputStreamDataInput, arr: Array[Long], offset: Int, length: Int): Array[Long] = {
    var i = offset
    val end = offset + length
    while (i < end) {
      arr(i) = in.readVLong
      i += 1
    }
    arr
  }

  def packLongArray(arr: Array[Long]): Array[Byte] = {
    val size = arr.length
    val baos = new ByteArrayOutputStream(size * 4)
    val out = new OutputStreamDataOutput(baos)

    // no version
    // list size
    out.writeVInt(size)
    // encode list
    arr.foreach { v => out.writeVLong(v) }
    baos.flush()
    baos.toByteArray()
  }

  def unpackLongArray(bytes: Array[Byte], offset: Int, length: Int): Array[Long] = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))
    readRawList(in, in.readVInt())
  }
}
