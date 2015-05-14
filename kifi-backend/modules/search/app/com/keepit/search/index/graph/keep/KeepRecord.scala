package com.keepit.search.index.graph.keep

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.{ Library, Hashtag, Keep }
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import com.keepit.search.index.Searcher
import org.apache.lucene.store.{ InputStreamDataInput, OutputStreamDataOutput }
import org.joda.time.DateTime
import scala.collection.JavaConversions._
import play.api.libs.json.Json
import com.keepit.common.time._

case class KeepRecord(title: Option[String], url: String, keptAt: DateTime, libraryId: Id[Library], externalId: ExternalId[Keep], note: Option[String], tags: Set[Hashtag])

object KeepRecord {
  def fromKeepAndTags(keep: Keep, tags: Set[Hashtag]): KeepRecord = KeepRecord(keep.title, keep.url, keep.keptAt, keep.libraryId.get, keep.externalId, keep.note.filter(_.nonEmpty), tags)

  implicit def toByteArray(record: KeepRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    out.writeByte(2) // version
    out.writeString(record.title.getOrElse(""))
    out.writeString(record.url)
    out.writeLong(record.keptAt.getMillis)
    out.writeLong(record.libraryId.id)
    out.writeString(record.externalId.id)
    out.writeString(record.note.getOrElse(""))
    out.writeStringSet(record.tags.map(_.tag))
    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): KeepRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version > 2) {
      throw new Exception(s"invalid data [version=${version}]")
    }

    val title = Some(in.readString()).filter(_.nonEmpty)
    val url = in.readString()
    val keptAt = in.readLong().toDateTime
    val libraryId = Id[Library](in.readLong())
    val id = ExternalId[Keep](in.readString())
    val note = if (version < 2) None else Some(in.readString()).filter(_.nonEmpty)
    val tags = in.readStringSet().map(Hashtag(_)).toSet
    KeepRecord(title, url, keptAt, libraryId, id, note, tags)
  }

  implicit val format = Json.format[KeepRecord]

  def retrieve(keepSearcher: Searcher, keepId: Id[Keep]): Option[KeepRecord] = {
    keepSearcher.getDecodedDocValue(KeepFields.recordField, keepId.id)
  }
}
