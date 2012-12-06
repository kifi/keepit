package com.keepit.search

import org.apache.lucene.store.OutputStreamDataOutput
import org.apache.lucene.store.InputStreamDataInput
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.Arrays
import javax.xml.bind.DatatypeConverter._

object IdFilterCompressor {
  def toByteArray(ids: Set[Long]): Array[Byte] = {
    val arr = ids.toArray
    Arrays.sort(arr)
    val baos = new ByteArrayOutputStream(arr.length * 4)
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(2)
    // a placeholder for 16-bit simple checksum (v2 and after)
    out.writeByte(0)
    out.writeByte(0)
    // size
    out.writeVInt(arr.length)
    // TODO: obfuscation method
    var current = 0L
    arr.foreach{ id  =>
      out.writeVLong(id - current)
      current = id
    }
    baos.close()

    val bytes = baos.toByteArray()
    val checksum = computeChecksum(bytes, 3, bytes.length)
    bytes(1) = (checksum >> 8).toByte
    bytes(2) = checksum.toByte
    bytes
  }

  def toSet(bytes: Array[Byte]): Set[Long]= {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes))
    var idSet = Set.empty[Long]
    var salt: Byte = 0

    val version = in.readByte().toInt
    if (version > 1) {

      if (bytes.length < 3) {
        throw new IdFilterCompressorException("invalid data [version=%d,length=%d]".format(version, bytes.length))
      }
      val checksum = (in.readByte().toInt & 0xFF) << 8 | (in.readByte().toInt & 0xFF)
      if (checksum != computeChecksum(bytes, 3, bytes.length)) {
        throw new IdFilterCompressorException("invalid data [checksum error]")
      }
    }
    val size = in.readVInt()
    var current = 0L;
    var i = 0
    while (i < size) {
      val id = current + in.readVLong
      idSet += id
      current = id
      i += 1
    }
    idSet
  }

  private def computeChecksum(bytes: Array[Byte], start: Int, end: Int) = {
    var i = start
    var sum = 0
    while (i < end) {
      sum += (bytes(i).toInt & 0xFF) << (sum % 7)
      i += 1
    }
    sum / 65521 // the largest prime number smaller than 2^16
  }

  def fromSetToBase64(ids: Set[Long]):String = printBase64Binary(toByteArray(ids))

  def fromBase64ToSet(base64: String) = {
    if (base64.length == 0) Set.empty[Long] else toSet(parseBase64Binary(base64))
  }
}

class IdFilterCompressorException(msg: String) extends Exception(msg)
