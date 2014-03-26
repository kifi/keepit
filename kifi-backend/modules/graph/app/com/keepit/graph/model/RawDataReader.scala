package com.keepit.graph.model

trait RawDataReader {
  protected def readDouble(offset: Int): Double
  protected def readFloat(offset: Int): Float
  protected def readInt(offset: Int): Int
  protected def readLong(offset: Int): Long
  protected def readByte(offset: Int): Byte
  protected def readShort(offset: Int): Short
}
