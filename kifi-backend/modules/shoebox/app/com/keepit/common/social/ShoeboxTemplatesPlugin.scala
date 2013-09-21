package com.keepit.common.social

import play.api.Application
import play.api.data.Form
import play.api.i18n.Messages
import play.api.mvc.Request
import play.api.templates.Html
import securesocial.controllers.DefaultTemplatesPlugin
import com.keepit.common.logging.Logging

class ShoeboxTemplatesPlugin(app: Application) extends DefaultTemplatesPlugin(app) with Logging {
  override def getLoginPage[A](
      implicit request: Request[A], form: Form[(String, String)], msg: Option[String]): Html = {
    log.info(s"[getLoginPage] request=$request form=$form")
    if (form != null) {
      super.getLoginPage(request, form, msg)
    } else {
      views.html.website.welcome(msg = msg.map(Messages(_)) orElse request.flash.get("error"), skipLetMeIn = true)
    }
  }
}