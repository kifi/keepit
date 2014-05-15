package com.keepit.eliza.model


case class ThreadEmailInfo(pageUrl: String, pageName: String, pageTitle: String, heroImageUrl: Option[String], pageDescription: Option[String], participants: Seq[String], conversationStarter: String, unsubUrl: Option[String], muteUrl: Option[String])

case class ExtendedThreadItem(senderShortName: String, senderFullName: String, imageUrl: Option[String], messageText: String, textLookHeres: Seq[String], imageLookHereUrls: Seq[String])
