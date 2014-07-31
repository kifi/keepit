package com.keepit.search.graph.keep

import com.keepit.common.db.ExternalId
import com.keepit.model.Keep
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import org.apache.lucene.store.{ InputStreamDataInput, OutputStreamDataOutput }

case class KeepRecord(title: Option[String], url: String, createdAt: Long, externalId: ExternalId[Keep])

object KeepRecord {
  def apply(keep: Keep): KeepRecord = KeepRecord(keep.title, keep.url, keep.createdAt.getMillis, keep.externalId)

  implicit def toByteArray(record: KeepRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    out.writeByte(1) // version
    out.writeString(record.title.getOrElse(""))
    out.writeString(record.url)
    out.writeLong(record.createdAt)
    out.writeString(record.externalId.id)
    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): KeepRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version > 1) {
      throw new Exception(s"invalid data [version=${version}]")
    }
    val title = Some(in.readString()).filter(_.nonEmpty)
    val url = in.readString()
    val createdAt = in.readLong()
    val id = ExternalId[Keep](in.readString())
    KeepRecord(title, url, createdAt, id)
  }
}
