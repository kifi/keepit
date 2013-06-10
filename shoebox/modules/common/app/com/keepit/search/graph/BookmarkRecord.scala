package com.keepit.search.graph


import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

case class BookmarkRecord(uriId: Long, title: String, url: String, createdAt: Long, isPrivate: Boolean)

object BookmarkRecordSerializer {
  implicit def toByteArray(r: BookmarkRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(1)
    out.writeLong(r.uriId)
    out.writeString(r.title)
    out.writeString(r.url)
    out.writeLong(r.createdAt)
    out.writeByte(if (r.isPrivate) 1 else 0)

    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): BookmarkRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version != 1) {
      throw new Exception(s"invalid data [version=${version}]")
    }

    BookmarkRecord(in readLong(), in.readString(), in.readString(), in.readLong(), in.readByte() == 1)
  }
}

