package com.keepit.cortex.store

import java.io._
import scala.collection.mutable

object StoreUtil {
  object FloatArrayFormmater {
    def toBinary(arr: Array[Float]): Array[Byte] = {
      val bs = new ByteArrayOutputStream(arr.size * 4)
      val os = new DataOutputStream(bs)
      arr.foreach { os.writeFloat }
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
      while (n < N) {
        arr(n) = is.readFloat()
        n += 1
      }
      is.close()
      arr
    }
  }

  object DenseWordVecFormatter {

    private def countChars(tokens: Seq[String]) = tokens.map { _.length }.sum
    private val CHAR_SIZE = 2
    private val FLOAT_SIZE = 4
    private val SEP = '|'

    /**
     * word2vec like binary format. example: vocSize = 3, vector dim = 2
     * 3|2'\n'
     * apple|0.3f0.7f'\n'
     * orange|1f0f'\n'
     * tea|0.4f0.3f'\n'
     *
     * WARN: This is not compatible with word2vec binary model file generated by C code. (They use SINGLE BYTE char)
     */
    def toBinary(dimension: Int, mapper: Map[String, Array[Float]]): Array[Byte] = {
      val vocSize = mapper.size
      val numLines = vocSize + 1
      val extraCharsPerLine = 2 // SEP and '\n'
      val totalSize = countChars(mapper.keys.map { _.trim }.toSeq) * CHAR_SIZE + numLines * extraCharsPerLine * CHAR_SIZE + FLOAT_SIZE * dimension * vocSize
      val bs = new ByteArrayOutputStream(totalSize)
      val os = new DataOutputStream(bs)

      // header
      os.writeInt(vocSize); os.writeChar(SEP); os.writeInt(dimension); os.writeChar('\n')
      // lines
      for ((key, arr) <- mapper) {
        var n = 0
        val trimed = key.trim()
        while (n < trimed.size) { os.writeChar(trimed(n)); n += 1 }
        os.writeChar(SEP)
        arr.foreach { os.writeFloat(_) }
        os.writeChar('\n')
      }
      os.close()
      val rv = bs.toByteArray()
      bs.close()
      rv
    }

    def fromBinary(bytes: Array[Byte]): (Int, Map[String, Array[Float]]) = {
      val is = new DataInputStream(new ByteArrayInputStream(bytes))
      // read header
      val vocSize = is.readInt()
      is.readChar()
      val dim = is.readInt()
      is.readChar()

      val mapper = mutable.Map[String, Array[Float]]()

      //read lines
      var n = 0
      var ch = ' '
      var i = 0

      while (n < vocSize) {
        n += 1
        val token = new StringBuilder
        ch = is.readChar()
        while (ch != SEP) { token.append(ch); ch = is.readChar() }

        val arr = new Array[Float](dim)
        i = 0
        while (i < dim) { arr(i) = is.readFloat(); i += 1 }

        is.readChar() // skip '\n'
        mapper += token.toString -> arr
      }
      is.close()
      (dim, mapper.toMap)
    }
  }
}
