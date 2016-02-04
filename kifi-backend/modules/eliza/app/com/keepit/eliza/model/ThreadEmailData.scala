package com.keepit.eliza.model

import com.keepit.common.crypto.PublicId
import com.keepit.model.{ Keep, NormalizedURI, User }
import com.keepit.eliza.util.MessageSegment
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.rover.model.RoverUriSummary

case class ThreadEmailInfo(
  uriId: Id[NormalizedURI],
  keepId: PublicId[Keep],
  pageName: String,
  pageTitle: String,
  isInitialEmail: Boolean,
  heroImageUrl: Option[String],
  pageDescription: Option[String],
  participants: Seq[String],
  conversationStarter: Option[String],
  invitedByUser: Option[User],
  unsubUrl: Option[String],
  muteUrl: Option[String],
  readTimeMinutes: Option[Int],
  nonUserAccessToken: Option[String])

case class ExtendedThreadItem(senderShortName: String, senderFullName: String, imageUrl: Option[String], segments: Seq[MessageSegment])

case class ThreadEmailData(
  thread: MessageThread,
  allUserIds: Set[Id[User]],
  allUsers: Map[Id[User], User],
  allUserImageUrls: Map[Id[User], String],
  uriSummary: Option[RoverUriSummary])
