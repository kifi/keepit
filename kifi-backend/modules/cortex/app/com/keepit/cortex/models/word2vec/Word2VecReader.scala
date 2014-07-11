package com.keepit.cortex.models.word2vec

import scala.collection.mutable
import java.nio._
import java.io._

// word2vec I/O. C Format compatible. Single Char Byte, Little Endian For Floats.
class Word2VecReader {

  def fromBinary(bytes: Array[Byte]): (Int, Map[String, Array[Float]]) = {
    val in = new DataInputStream(new ByteArrayInputStream(bytes))
    fromDataStream(in)
  }

  def fromFile(fileName: String): (Int, Map[String, Array[Float]]) = {
    val dataStream = new DataInputStream(new FileInputStream(new File(fileName)))
    fromDataStream(dataStream)
  }

  def fromDataStream(dataStream: DataInputStream): (Int, Map[String, Array[Float]]) = {
    var ch = dataStream.readByte().asInstanceOf[Char]
    var str = ""
    // read header line: vocabulary size & vector dim
    while (ch != '\n') {
      str += ch
      ch = dataStream.readByte().asInstanceOf[Char]
    }

    val header = str.split(" ")
    val vocSize = Integer.parseInt(header(0))
    val vecDim = Integer.parseInt(header(1))
    var wordCnt = 0
    val wordVecMap = mutable.Map[String, Array[Float]]()
    var word = ""
    val chunkSize = vecDim * 4
    val bytes = new Array[Byte](chunkSize)

    // lines of mixed string and Float array. Formatted as:  word byte byte byte ..... byte '\n'
    while (wordCnt < vocSize) {
      wordCnt += 1
      val vec = new Array[Float](vecDim)

      // read word
      word = ""
      ch = dataStream.readByte().asInstanceOf[Char]
      while (ch != ' ') {
        word += ch
        ch = dataStream.readByte().asInstanceOf[Char]
      }

      // read floats
      dataStream.read(bytes, 0, chunkSize)
      val buffed = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      (0 until vecDim).foreach { i => vec(i) = buffed.getFloat(4 * i) }
      wordVecMap += word -> vec

      // skip end of line
      dataStream.readByte()
    }
    (vecDim, wordVecMap.toMap)
  }
}

class Word2VecWriter() {
  private def countChars(tokens: Seq[String]) = tokens.map { _.length }.sum
  private val CHAR_SIZE = 1 // C style. not Java default.
  private val FLOAT_SIZE = 4
  private val SEP = ' '
  private val SPACE_BYTE = (' ' & 0x00FF).toByte
  private val NEWLINE_BYTE = ('\n' & 0x00FF).toByte

  def toBinary(dimension: Int, mapper: Map[String, Array[Float]]): Array[Byte] = {
    val vocSize = mapper.size
    val numLines = vocSize + 1
    val extraCharsPerLine = 2 // SEP and '\n'

    val header = vocSize.toString + " " + dimension.toString() + "\n"

    val totalSize = countChars(mapper.keys.map { _.trim }.toSeq) * CHAR_SIZE + vocSize * extraCharsPerLine * CHAR_SIZE + FLOAT_SIZE * dimension * vocSize + header.length

    val buf = ByteBuffer.allocate(totalSize)
    buf.order(ByteOrder.LITTLE_ENDIAN)

    header.toCharArray().foreach { ch =>
      buf.put((ch & 0x00FF).toByte)
    }

    // lines
    for ((key, arr) <- mapper) {
      var n = 0
      val trimed = key.trim()
      while (n < trimed.size) {
        val byte = (trimed(n) & 0x00FF).toByte
        buf.put(byte)
        n += 1
      }
      buf.put(SPACE_BYTE)
      arr.foreach { buf.putFloat(_) }
      buf.put(NEWLINE_BYTE)
    }

    buf.array()
  }

}
