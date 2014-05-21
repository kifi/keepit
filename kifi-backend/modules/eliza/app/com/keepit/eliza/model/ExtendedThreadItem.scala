package com.keepit.eliza.model
import com.keepit.eliza.commanders.MessageSegment
import com.keepit.model.User


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
