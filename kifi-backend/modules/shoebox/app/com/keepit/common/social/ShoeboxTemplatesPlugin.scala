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
  // todo: wtf? Kill this (give it an obscure route, redirect any requests to our login page), we can handle the log in form ourselves
  override def getLoginPage[A](
      implicit request: Request[A], form: Form[(String, String)], msg: Option[String]): Html = {
    log.info(s"[getLoginPage] request=$request form=$form")
    val newSignup = current.configuration.getBoolean("newSignup").getOrElse(false)
    views.html.website.welcome(msg = msg.map(Messages(_)) orElse request.flash.get("error"),
      skipLetMeIn = true, newSignup = newSignup)
  }
}
