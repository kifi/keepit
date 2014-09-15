package com.keepit.common.mail.template

import com.kifi.macros.json
import play.twirl.api.Html

import scala.concurrent.Future

@json case class EmailTips(value: String) extends AnyVal

object EmailTips {
  val FriendRecommendations = EmailTips("PYMK")
}

trait TipTemplate {
  def render(emailToSend: EmailToSend): Future[Option[Html]]
}
