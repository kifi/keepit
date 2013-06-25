package com.keepit.search.comment

import com.keepit.common.db.{ExternalId, Id, State}
import com.keepit.model.{Comment, CommentPermission, CommentRecipient, NormalizedURI, User}
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

case class CommentRecord(text: String, createdAt: Long, userId: Id[User], uriId: Id[NormalizedURI], pageTitle: String, permission: State[CommentPermission], externalId: ExternalId[Comment])

object CommentRecordSerializer {
  implicit def toByteArray(r: CommentRecord): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(1)
    out.writeString(r.text)
    out.writeLong(r.createdAt)
    out.writeLong(r.userId.id)
    out.writeLong(r.uriId.id)
    out.writeString(r.pageTitle)
    out.writeString(r.permission.toString)
    out.writeString(r.externalId.id)

    out.close()
    baos.close()

    val bytes = baos.toByteArray()
    bytes
  }

  implicit def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): CommentRecord = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt
    if (version > 1) {
      throw new Exception(s"invalid data [version=${version}]")
    }

    CommentRecord(
      in.readString(),                          // text
      in.readLong(),                            // createdAt
      Id[User](in.readLong()),                  // userId
      Id[NormalizedURI](in.readLong()),         // uriId
      in.readString(),                          // pageTitle
      State[CommentPermission](in.readString()),// permission
      ExternalId[Comment](in.readString())      // externalId
    )
  }
}

