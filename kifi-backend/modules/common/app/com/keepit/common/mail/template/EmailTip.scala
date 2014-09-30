package com.keepit.common.mail.template

import com.keepit.heimdal.SimpleContextData
import com.kifi.macros.json
import play.twirl.api.Html

import scala.concurrent.Future

@json case class EmailTip(value: String) extends AnyVal

object EmailTip {
  val FriendRecommendations = EmailTip("PYMK")

  implicit def toContextData(tip: EmailTip): SimpleContextData =
    SimpleContextData.toContextStringData(tip.value)

  implicit def fromContextData(ctx: SimpleContextData): Option[EmailTip] =
    SimpleContextData.fromContextStringData(ctx) map EmailTip.apply
}

trait TipTemplate {
  def render(emailToSend: EmailToSend): Future[Option[Html]]
}
