package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.commanders.FeatureWaitlistCommander
import com.keepit.model.FeatureWaitlistEntry
import com.keepit.common.db.ExternalId

import javax.mail.internet.{ InternetAddress, AddressException }

import com.google.inject.Inject

import play.api.mvc.Action

class FeatureWaitlistController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  commander: FeatureWaitlistCommander)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def waitList() = Action(parse.tolerantJson) { request =>
    try {
      val email = (request.body \ "email").as[String]
      val feature = (request.body \ "feature").as[String]
      val extIdOpt = (request.body \ "extId").asOpt[String].map(ExternalId[FeatureWaitlistEntry](_))
      val userAgent = request.headers.get("User-Agent").getOrElse("")
      val parsedEmail = new InternetAddress(email)
      parsedEmail.validate()
      Ok(commander.waitList(email, feature, userAgent, extIdOpt).id)
    } catch {
      case ex: play.api.libs.json.JsResultException => BadRequest("invalid/incomplete json")
      case ex: AddressException => BadRequest("invalid email address")
    }

  }

}
