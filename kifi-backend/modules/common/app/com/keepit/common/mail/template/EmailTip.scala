package com.keepit.common.mail.template

import com.keepit.common.logging.Logging
import com.keepit.heimdal.SimpleContextData
import com.kifi.macros.json

@json case class EmailTip(value: String) extends AnyVal

object EmailTip {
  // EmailTip.value is kept short b/c it is encoded in URLs;
  // of course, each value should be unique
  val FriendRecommendations = EmailTip("PYMK")
  val ConnectFacebook = EmailTip("cFB")
  val ConnectLinkedIn = EmailTip("cLI")
  val ImportGmailContacts = EmailTip("gmail")
  val InstallExtension = EmailTip("ext")

  implicit def toContextData(tip: EmailTip): SimpleContextData =
    SimpleContextData.toContextStringData(tip.value)

  implicit def fromContextData(ctx: SimpleContextData): Option[EmailTip] =
    SimpleContextData.fromContextStringData(ctx) map EmailTip.apply
}

trait TipTemplate extends Logging
