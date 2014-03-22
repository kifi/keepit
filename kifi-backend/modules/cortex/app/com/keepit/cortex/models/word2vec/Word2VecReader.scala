package com.keepit.cortex.models.word2vec

import scala.collection.mutable
import java.nio._
import java.io._

// read binary output of word2vec
class Word2VecReader {

  def fromBinary(fileName: String): Map[String,Array[Float]] = {
    val dataStream = new DataInputStream(new FileInputStream(new File(fileName)))

    var ch = dataStream.readByte().asInstanceOf[Char]
    var str = ""
    // read header line: vocabulary size & vector dim
    while (ch != '\n'){
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
    while(wordCnt < vocSize){
      wordCnt += 1
      val vec = new Array[Float](vecDim)

      // read word
      word = ""
      ch = dataStream.readByte().asInstanceOf[Char]
      while(ch != ' ') {
        word += ch
        ch = dataStream.readByte().asInstanceOf[Char]
      }

      // read floats
      dataStream.read(bytes, 0, chunkSize)
      val buffed = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      (0 until vecDim).foreach{ i => vec(i) = buffed.getFloat(4 * i)}
      wordVecMap += word -> vec

      // skip end of line
      dataStream.readByte()
    }
    wordVecMap.toMap
  }
}
