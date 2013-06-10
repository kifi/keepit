package com.keepit.search.index

import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

case class ArticleRecord(id: Long, title: String, url: String)

object ArticleRecordSerializer {
  implicit def toByteArray(r: ArticleRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(1)
    out.writeVLong(r.id)
    out.writeString(r.title)
    out.writeString(r.url)

    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): ArticleRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version != 1) {
      throw new Exception(s"invalid data [version=${version}]")
    }

    ArticleRecord(in.readVLong(), in.readString(), in.readString())
  }
}

