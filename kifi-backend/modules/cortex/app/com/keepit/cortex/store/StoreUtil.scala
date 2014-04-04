package com.keepit.cortex.store

import java.io._


object StoreUtil {
  object FloatArrayFormmater {
    def toBinary(arr: Array[Float]): Array[Byte] = {
      val bs = new ByteArrayOutputStream(arr.size * 4)
      val os = new DataOutputStream(bs)
      arr.foreach{os.writeFloat}
      os.close()
      val rv = bs.toByteArray()
      bs.close()
      rv
    }

    def fromBinary(bytes: Array[Byte]): Array[Float] = {
      val is = new DataInputStream(new ByteArrayInputStream(bytes))
      val N = bytes.size / 4
      val arr = new Array[Float](N)
      var n = 0
      while ( n < N ){
        arr(n) = is.readFloat()
        n += 1
      }
      is.close()
      arr
    }
  }
}
