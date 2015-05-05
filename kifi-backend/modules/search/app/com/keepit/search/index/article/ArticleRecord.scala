package com.keepit.search.index.article

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

case class ArticleRecord(title: Option[String], url: String, id: Id[NormalizedURI])

object ArticleRecord {
  implicit def toByteArray(r: ArticleRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(2)
    out.writeString(r.title.getOrElse(""))
    out.writeString(r.url)
    out.writeVLong(r.id.id)

    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): ArticleRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version > 2) {
      throw new Exception(s"invalid data [version=${version}]")
    }

    ArticleRecord(
      Some(in.readString()).filter(_.nonEmpty), // title
      in.readString(), // url
      Id[NormalizedURI](in.readVLong()))
  }
}

