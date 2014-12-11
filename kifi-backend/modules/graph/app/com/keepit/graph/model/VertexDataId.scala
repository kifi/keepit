package com.keepit.graph.model

import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.search.index.message.ThreadContent
import play.api.libs.json.{ JsNumber, Writes, Reads, Format }
import com.keepit.graph.manager.LDATopicId
import com.keepit.abook.model.EmailAccountInfo

case class VertexDataId[V <: VertexDataReader](id: Long) // extends AnyVal

object VertexDataId {
  implicit def format[V <: VertexDataReader]: Format[VertexDataId[V]] = Format(Reads.of[Long].map(VertexDataId[V](_)), Writes(id => JsNumber(id.id)))

  implicit def fromUserId(userId: Id[User]): VertexDataId[UserReader] = VertexDataId(userId.id)
  implicit def toUserId(userReaderId: VertexDataId[UserReader]): Id[User] = Id(userReaderId.id)

  implicit def fromNormalizedUriId(normalizedUriId: Id[NormalizedURI]): VertexDataId[UriReader] = VertexDataId(normalizedUriId.id)
  implicit def toNormalizedUriId(uriReaderId: VertexDataId[UriReader]): Id[NormalizedURI] = Id(uriReaderId.id)

  implicit def fromTagId(tagId: Id[Collection]): VertexDataId[TagReader] = VertexDataId(tagId.id)
  implicit def toTagId(tagReaderId: VertexDataId[TagReader]): Id[Collection] = Id(tagReaderId.id)

  implicit def fromThreadContentId(threadContentId: Id[ThreadContent]): VertexDataId[DiscussionReader] = VertexDataId(threadContentId.id)
  implicit def toThreadContentId(discussionReaderId: VertexDataId[DiscussionReader]): Id[ThreadContent] = Id(discussionReaderId.id)

  implicit def fromSocialUserIdToFacebookAccountId(socialUserId: Id[SocialUserInfo]): VertexDataId[FacebookAccountReader] = VertexDataId(socialUserId.id)
  implicit def fromFacebookAccountIdtoSocialUserId(facebookAccountReaderId: VertexDataId[FacebookAccountReader]): Id[SocialUserInfo] = Id(facebookAccountReaderId.id)

  implicit def fromSocialUserIdToLinkedInAccountId(socialUserId: Id[SocialUserInfo]): VertexDataId[LinkedInAccountReader] = VertexDataId(socialUserId.id)
  implicit def fromLinkedInAccountIdtoSocialUserId(linkedInAccountReaderId: VertexDataId[LinkedInAccountReader]): Id[SocialUserInfo] = Id(linkedInAccountReaderId.id)

  implicit def fromKeepId(keepId: Id[Keep]): VertexDataId[KeepReader] = VertexDataId(keepId.id)
  implicit def toKeepId(keepReaderId: VertexDataId[KeepReader]): Id[Keep] = Id(keepReaderId.id)

  implicit def fromLDATopicId(ldaTopicId: LDATopicId): VertexDataId[LDATopicReader] = VertexDataId(ldaTopicId.toLong)
  implicit def toLDATopicId(ldaTopicReaderId: VertexDataId[LDATopicReader]): LDATopicId = LDATopicId.fromLong(ldaTopicReaderId.id)

  implicit def fromEmailAccountId(emailAccountId: Id[EmailAccountInfo]): VertexDataId[EmailAccountReader] = VertexDataId(emailAccountId.id)
  implicit def toEmailAccountId(emailAccountReaderId: VertexDataId[EmailAccountReader]): Id[EmailAccountInfo] = Id(emailAccountReaderId.id)

  implicit def fromABookId(abookId: Id[ABookInfo]): VertexDataId[AddressBookReader] = VertexDataId(abookId.id)
  implicit def toABookId(addressBookReaderId: VertexDataId[AddressBookReader]): Id[ABookInfo] = Id(addressBookReaderId.id)

  implicit def fromLibraryId(libId: Id[Library]): VertexDataId[LibraryReader] = VertexDataId(libId.id)
  implicit def toLibraryId(libReaderId: VertexDataId[LibraryReader]): Id[Library] = Id(libReaderId.id)
}
