package com.keepit.eliza.model


case class ThreadEmailInfo(pageUrl: String, pageName: String, heroImageUrl: String, pageDescription: String, participants: Seq[String], conversationStarter: String, unsubUrl: String, muteUrl: String)

case class ExtendedThreadItem(senderFirstName: String, senderLastName: String, imageUrl: String, messageText: String, textLookHeres: Seq[String], imageLookHereUrls: Seq[String])
