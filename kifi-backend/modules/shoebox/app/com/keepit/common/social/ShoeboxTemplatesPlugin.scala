package com.keepit.common.social

import com.keepit.common.logging.Logging
import play.api.Application
import play.api.data.Form
import play.api.mvc.Request
import play.twirl.api.Html
import securesocial.controllers.DefaultTemplatesPlugin

class ShoeboxTemplatesPlugin(app: Application) extends DefaultTemplatesPlugin(app) with Logging {

  // todo: wtf? Kill this (give it an obscure route, redirect any requests to our login page), we can handle the log in form ourselves
  override def getLoginPage[A](implicit request: Request[A], form: Form[(String, String)], msg: Option[String]): Html = {
    log.info(s"[getLoginPage] request=$request form=$form")
    Html(s"""<script>window.location="${com.keepit.controllers.core.routes.AuthController.loginPage}"</script>""")
  }
}
