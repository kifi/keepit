package com.keepit.search.graph.library

import com.keepit.common.db.ExternalId
import com.keepit.model.Library
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import org.apache.lucene.store.{InputStreamDataInput, OutputStreamDataOutput}

case class LibraryRecord(title: String, description: String, id: ExternalId[Library])

object LibraryRecord {
  implicit def toByteArray(record: LibraryRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    out.writeByte(1) // version
    out.writeString(record.title)
    out.writeString(record.description)
    out.writeString(record.id.id)

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
    val description = in.readString()
    val id = ExternalId[Library](in.readString())
    LibraryRecord(title, description, id)
  }
}
