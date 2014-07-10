package com.keepit.search.graph.bookmark

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.Keep
import com.keepit.model.NormalizedURI
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

case class BookmarkRecord(title: String, url: String, createdAt: Long, isPrivate: Boolean, uriId: Id[NormalizedURI], externalId: Option[ExternalId[Keep]])

object BookmarkRecordSerializer {
  implicit def toByteArray(r: BookmarkRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(3)
    out.writeString(r.title)
    out.writeString(r.url)
    out.writeLong(r.createdAt)
    out.writeByte(if (r.isPrivate) 1 else 0)
    out.writeLong(r.uriId.id)
    r.externalId.map(x => out.writeString(x.id))

    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): BookmarkRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version > 3) {
      throw new Exception(s"invalid data [version=${version}]")
    }

    BookmarkRecord(
      in.readString(), // title
      in.readString(), // url
      in.readLong(), // createdAt
      in.readByte() == 1, // isPrivate
      Id[NormalizedURI](in readLong ()),
      if (version >= 3) Some(ExternalId[Keep](in.readString())) else None)
  }
}

