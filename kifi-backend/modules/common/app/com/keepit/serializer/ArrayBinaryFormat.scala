package com.keepit.serializer

import java.nio.ByteBuffer

object ArrayBinaryFormat {

  val intArrayFormat = new BinaryFormat[Array[Int]] {
    protected def reads(data: Array[Byte], offset: Int, length: Int): Array[Int] = {
      val intBuffer = ByteBuffer.wrap(data, offset, length).asIntBuffer
      val outArray = new Array[Int]((data.length - 1) / 4)
      intBuffer.get(outArray)
      outArray
    }

    protected def writes(prefix: Byte, value: Array[Int]): Array[Byte] = {
      val byteBuffer = ByteBuffer.allocate(1 + value.length * 4)
      byteBuffer.put(prefix) // we have something
      byteBuffer.asIntBuffer.put(value)
      byteBuffer.array
    }
  }

  val longArrayFormat = new BinaryFormat[Array[Long]] {
    protected def reads(data: Array[Byte], offset: Int, length: Int): Array[Long] = {
      val longBuffer = ByteBuffer.wrap(data, offset, length).asLongBuffer
      val outArray = new Array[Long]((data.length - 1) / 8)
      longBuffer.get(outArray)
      outArray
    }

    protected def writes(prefix: Byte, value: Array[Long]): Array[Byte] = {
      val byteBuffer = ByteBuffer.allocate(1 + value.length * 8)
      byteBuffer.put(prefix) // we have something
      byteBuffer.asLongBuffer.put(value)
      byteBuffer.array
    }
  }
}
