package com.keepit.eliza.model
import com.keepit.eliza.commanders.MessageSegment


case class ThreadEmailInfo(
  pageUrl: String,
  pageName: String,
  pageTitle: String,
  heroImageUrl: Option[String],
  pageDescription: Option[String],
  participants: Seq[String],
  conversationStarter: String,
  unsubUrl: Option[String],
  muteUrl: Option[String],
  readTimeMinutes: Option[Int])

case class ExtendedThreadItem(senderShortName: String, senderFullName: String, imageUrl: Option[String], segments: Seq[MessageSegment])
