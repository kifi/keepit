package com.keepit.search.index.graph.library

import com.keepit.common.db.Id
import com.keepit.model._
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import org.apache.lucene.store.{ InputStreamDataInput, OutputStreamDataOutput }

case class LibraryRecord(id: Id[Library], name: String, description: Option[String], color: Option[LibraryColor], ownerId: Id[User], slug: LibrarySlug, orgId: Option[Id[Organization]])

object LibraryRecord {
  def apply(library: DetailedLibraryView): LibraryRecord = LibraryRecord(library.id.get, library.name, library.description, library.color, library.ownerId, library.slug, library.orgId)

  implicit def toByteArray(record: LibraryRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    out.writeByte(4) // version
    out.writeLong(record.id.id)
    out.writeString(record.name)
    out.writeString(record.description.getOrElse(""))
    out.writeString(record.color.map(_.hex).getOrElse(""))
    out.writeLong(record.ownerId.id)
    out.writeString(record.slug.value)
    out.writeLong(record.orgId.map { _.id }.getOrElse(-1))

    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): LibraryRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    version match {
      case 3 =>
        val id = Id[Library](in.readLong())
        val name = in.readString()
        val description = Some(in.readString()).filter(_.nonEmpty)
        val color = Some(in.readString()).filter(_.nonEmpty).map(LibraryColor.apply(_))
        val ownerId = Id[User](in.readLong())
        val slug = LibrarySlug(in.readString())
        LibraryRecord(id, name, description, color, ownerId, slug, None)
      case 4 =>
        val id = Id[Library](in.readLong())
        val name = in.readString()
        val description = Some(in.readString()).filter(_.nonEmpty)
        val color = Some(in.readString()).filter(_.nonEmpty).map(LibraryColor.apply(_))
        val ownerId = Id[User](in.readLong())
        val slug = LibrarySlug(in.readString())
        val orgIdLong = in.readLong()
        val orgId = if (orgIdLong > 0) Some(Id[Organization](orgIdLong)) else None
        LibraryRecord(id, name, description, color, ownerId, slug, orgId)

      case _ => throw new Exception(s"invalid data [version=${version}]")
    }
  }
}
