package com.keepit.controllers.admin

import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import play.api.mvc.Action
import play.api.Play
import play.api.http.ContentTypes

import com.google.inject.{Inject, Singleton}

@Singleton
class ElectronicMailLoggingController @Inject() (
  actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) {

  def doLog() = Action { implicit request =>
    request.body.asFormUrlEncoded match {
      case Some(b) =>
        val event = b.get("event").get.head
        val email = b.get("email").get.head
        val mail_id = b.get("mail_id").get.head
        log.info("got mail event:%s, %s, %s".format(event, email, mail_id))
        Ok
      case None =>
        BadRequest
    }
  }
}
