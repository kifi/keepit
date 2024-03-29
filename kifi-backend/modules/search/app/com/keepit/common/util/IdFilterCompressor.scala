package com.keepit.common.util

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import java.util.Arrays
import java.util.zip.Adler32

import com.keepit.search.util.LongArraySet
import org.apache.lucene.store.{ InputStreamDataInput, OutputStreamDataOutput }

object IdFilterCompressor extends IdFilterBase[LongArraySet] {

  override protected val emptySet: LongArraySet = LongArraySet.empty

  def toByteArray(ids: Set[Long]): Array[Byte] = {
    val arr = ids.toArray
    Arrays.sort(arr)
    val baos = new ByteArrayOutputStream(arr.length * 4)
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(2)
    // a placeholder for checksum (v2 and after)
    out.writeInt(0)
    // size
    out.writeVInt(arr.length)
    // ids
    var current = 0L
    arr.foreach { id =>
      out.writeVLong(id - current)
      current = id
    }
    baos.close()

    val bytes = baos.toByteArray()
    writeChecksum(bytes)
    bytes
  }

  private def writeChecksum(bytes: Array[Byte]) {
    val checksum = computeChecksum(bytes)
    bytes(1) = (checksum >> 24).toByte
    bytes(2) = (checksum >> 16).toByte
    bytes(3) = (checksum >> 8).toByte
    bytes(4) = (checksum).toByte
  }

  def toSet(bytes: Array[Byte]): LongArraySet = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes))

    val version = in.readByte().toInt
    if (version > 1) {

      if (bytes.length < 3) {
        throw new IdFilterCompressorException("invalid data [version=%d,length=%d]".format(version, bytes.length))
      }
      val checksum = in.readInt()
      if (checksum != computeChecksum(bytes)) {
        throw new IdFilterCompressorException("invalid data [checksum error]")
      }
    }
    val size = in.readVInt()
    var arr = new Array[Long](size)
    var current = 0L
    var i = 0
    while (i < size) {
      val id = current + in.readVLong
      arr(i) = id
      current = id
      i += 1
    }
    LongArraySet.from(arr)
  }

  private def computeChecksum(bytes: Array[Byte]) = {
    val adler32 = new Adler32
    adler32.update(bytes, 5, bytes.length - 5)
    adler32.getValue.toInt
  }

}
