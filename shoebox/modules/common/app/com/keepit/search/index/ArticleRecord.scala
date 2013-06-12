package com.keepit.search.index

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

case class ArticleRecord(title: String, url: String, id: Id[NormalizedURI], externalId: ExternalId[NormalizedURI])

object ArticleRecordSerializer {
  implicit def toByteArray(r: ArticleRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(1)
    out.writeString(r.title)
    out.writeString(r.url)
    out.writeVLong(r.id.id)
    out.writeString(r.externalId.toString)

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

    ArticleRecord(
      in.readString(),  // title
      in.readString(),  // url
      Id[NormalizedURI](in.readVLong()),
      ExternalId[NormalizedURI](in.readString()))
  }
}

