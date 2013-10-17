package com.keepit.serializer

import java.nio.ByteBuffer

object ArrayBinarySerializer {

  val intArraySerializer = new BinaryFormat[Array[Int]] {
    def reads(data: Array[Byte]): Array[Int] = {
      val intBuffer = ByteBuffer.wrap(data).asIntBuffer
      val outArray = new Array[Int](data.length/4)
      intBuffer.get(outArray)
      outArray
    }

    def writes(value: Array[Int]): Array[Byte] = {
      val byteBuffer = ByteBuffer.allocate(value.size*4)
      byteBuffer.asIntBuffer.put(value)
      byteBuffer.array
    }
  }

  val longArraySerializer = new BinaryFormat[Array[Long]] {
    def reads(data: Array[Byte]): Array[Long] = {
      val longBuffer = ByteBuffer.wrap(data).asLongBuffer
      val outArray = new Array[Long](data.length/8)
      longBuffer.get(outArray)
      outArray
    }

    def writes(value: Array[Long]): Array[Byte] = {
      val byteBuffer = ByteBuffer.allocate(value.size*8)
      byteBuffer.asLongBuffer.put(value)
      byteBuffer.array
    }
  }
}
