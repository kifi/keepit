package com.keepit.common.social

import play.api.Application
import play.api.data.Form
import play.api.i18n.Messages
import play.api.mvc.Request
import play.api.templates.Html
import securesocial.controllers.DefaultTemplatesPlugin

class ShoeboxTemplatesPlugin(app: Application) extends DefaultTemplatesPlugin(app) {
  override def getLoginPage[A](
      implicit request: Request[A], form: Form[(String, String)], msg: Option[String]): Html = {
    views.html.website.welcome(msg = msg.map(Messages(_)) orElse request.flash.get("error"), skipLetMeIn = true)
  }
}
