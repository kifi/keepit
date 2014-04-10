package com.keepit.graph.model

import com.keepit.model.{Collection, NormalizedURI, User}
import com.keepit.common.db.Id
import com.keepit.search.message.ThreadContent

case class VertexDataId[V <: VertexDataReader](id: Long) // extends AnyVal

object VertexDataId {
  implicit def fromUserId(userId: Id[User]): VertexDataId[UserReader] = VertexDataId(userId.id)
  implicit def toUserId(userReaderId: VertexDataId[UserReader]): Id[User] = Id(userReaderId.id)

  implicit def fromNormalizedUriId(normalizedUriId: Id[NormalizedURI]): VertexDataId[UriReader] = VertexDataId(normalizedUriId.id)
  implicit def toNormalizedUriId(uriReaderId: VertexDataId[UriReader]): Id[NormalizedURI] = Id(uriReaderId.id)

  implicit def fromTagId(tagId: Id[Collection]): VertexDataId[TagReader] = VertexDataId(tagId.id)
  implicit def toTagId(tagReaderId: VertexDataId[TagReader]): Id[Collection] = Id(tagReaderId.id)

  implicit def fromThreadContentId(threadContentId: Id[ThreadContent]): VertexDataId[ThreadReader] = VertexDataId(threadContentId.id)
  implicit def toThreadContentId(threadReaderId: VertexDataId[ThreadReader]): Id[ThreadContent] = Id(threadReaderId.id)
}
