package com.keepit.search.graph.library

import com.keepit.common.db.Id
import com.keepit.model.Library
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import org.apache.lucene.store.{InputStreamDataInput, OutputStreamDataOutput}

case class LibraryRecord(name: String, description: Option[String], id: Id[Library])

object LibraryRecord {
  implicit def toByteArray(record: LibraryRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    out.writeByte(1) // version
    out.writeString(record.name)
    out.writeString(record.description.getOrElse(""))
    out.writeLong(record.id.id)

    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): LibraryRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version > 1) {
      throw new Exception(s"invalid data [version=${version}]")
    }
    val title = in.readString()
    val description = Some(in.readString()).filter(_.nonEmpty)
    val id = Id[Library](in.readLong())
    LibraryRecord(title, description, id)
  }
}
