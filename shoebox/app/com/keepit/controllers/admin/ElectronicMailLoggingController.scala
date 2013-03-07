package com.keepit.controllers.admin

import com.keepit.common.controller.AdminController
import play.api.mvc.Action
import play.api.Play
import play.api.http.ContentTypes

/**
 * Created with IntelliJ IDEA.
 * User: yonatan
 * Date: 1/13/13
 * Time: 12:18 AM
 * To change this template use File | Settings | File Templates.
 */
object ElectronicMailLoggingController extends AdminController  {

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
