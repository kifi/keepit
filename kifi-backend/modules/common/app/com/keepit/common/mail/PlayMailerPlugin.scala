package com.keepit.common.mail

import com.keepit.FortyTwoGlobal
import com.typesafe.plugin.{MailerAPI, MailerPlugin}

import play.api.Application

class PlayMailerPlugin(app: Application) extends MailerPlugin {
  def email: MailerAPI = {
    val global = app.global.asInstanceOf[FortyTwoGlobal]
    implicit val injector = global.injector
    global.inject[MailerAPI]
  }
}
