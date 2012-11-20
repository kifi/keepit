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
    out.writeVInt(1)
    // size
    out.writeVInt(arr.length)
    // TODO: obfuscation method
    var current = 0L
    arr.foreach{ id  =>
      out.writeVLong(id - current)
      current = id
    }
    baos.flush()
    baos.toByteArray()
  }

  def toSet(bytes: Array[Byte]): Set[Long]= {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes))
    var idSet = Set.empty[Long]

    val version = in.readVInt()
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

  def fromSetToBase64(ids: Set[Long]):String = printBase64Binary(toByteArray(ids))

  def fromBase64ToSet(base64: String) = {
    if (base64.length == 0) Set.empty[Long] else toSet(parseBase64Binary(base64))
  }
}
