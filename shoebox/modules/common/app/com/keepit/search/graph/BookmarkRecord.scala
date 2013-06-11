package com.keepit.search.graph


import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

case class BookmarkRecord(title: String, url: String, createdAt: Long, isPrivate: Boolean, uriId: Id[NormalizedURI], externalUriId: ExternalId[NormalizedURI])

object BookmarkRecordSerializer {
  implicit def toByteArray(r: BookmarkRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(1)
    out.writeString(r.title)
    out.writeString(r.url)
    out.writeLong(r.createdAt)
    out.writeByte(if (r.isPrivate) 1 else 0)
    out.writeLong(r.uriId.id)
    out.writeString(r.externalUriId.toString)

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

    BookmarkRecord(
      in.readString(),    // title
      in.readString(),    // url
      in.readLong(),      // createdAt
      in.readByte() == 1, // isPrivate
      Id[NormalizedURI](in readLong()),
      ExternalId[NormalizedURI](in.readString()))
  }
}

