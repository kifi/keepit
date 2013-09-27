package com.keepit.common.social

import com.keepit.common.logging.Logging

import play.api.Play.current
import play.api.data.Form
import play.api.i18n.Messages
import play.api.mvc.Request
import play.api.templates.Html
import play.api.{Play, Application}
import securesocial.controllers.DefaultTemplatesPlugin

class ShoeboxTemplatesPlugin(app: Application) extends DefaultTemplatesPlugin(app) with Logging {
  override def getLoginPage[A](
      implicit request: Request[A], form: Form[(String, String)], msg: Option[String]): Html = {
    log.info(s"[getLoginPage] request=$request form=$form")
    views.html.website.welcome(msg = msg.map(Messages(_)) orElse request.flash.get("error"),
      skipLetMeIn = true, passwordAuth = Play.isDev)
  }

}
