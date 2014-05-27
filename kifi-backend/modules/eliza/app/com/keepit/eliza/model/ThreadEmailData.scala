package com.keepit.eliza.model
import com.keepit.model.{URISummary, User}
import com.keepit.eliza.util.MessageSegment
import com.keepit.common.db.Id


case class ThreadEmailInfo(
  pageUrl: String,
  pageName: String,
  pageTitle: String,
  isInitialEmail: Boolean,
  heroImageUrl: Option[String],
  pageDescription: Option[String],
  participants: Seq[String],
  conversationStarter: String,
  invitedByUser: Option[User],
  unsubUrl: Option[String],
  muteUrl: Option[String],
  readTimeMinutes: Option[Int])

case class ExtendedThreadItem(senderShortName: String, senderFullName: String, imageUrl: Option[String], segments: Seq[MessageSegment])

case class ThreadEmailData(
  thread: MessageThread,
  allUserIds: Set[Id[User]],
  allUsers: Map[Id[User], User],
  allUserImageUrls: Map[Id[User], String],
  uriSummaryBig: URISummary,
  uriSummarySmall: URISummary,
  readTimeMinutesOpt: Option[Int]
)
