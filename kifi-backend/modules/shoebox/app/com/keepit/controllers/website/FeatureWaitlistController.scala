package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.commanders.FeatureWaitlistCommander
import com.keepit.common.db.ExternalId
import com.keepit.model.FeatureWaitlistEntry

import javax.mail.internet.{ InternetAddress, AddressException }

import com.google.inject.Inject

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Action

import scala.concurrent.Future

class FeatureWaitlistController @Inject() (
  val userActionsHelper: UserActionsHelper,
  commander: FeatureWaitlistCommander)
    extends UserActions with ShoeboxServiceController {

  def waitList() = Action.async(parse.tolerantJson) { request =>
    try {
      val email = (request.body \ "email").as[String]
      val feature = (request.body \ "feature").as[String]
      val extIdOpt = (request.body \ "extId").asOpt[String].map(ExternalId[FeatureWaitlistEntry])
      val userAgent = request.headers.get("User-Agent").getOrElse("")
      val parsedEmail = new InternetAddress(email)
      parsedEmail.validate()
      commander.waitList(email, feature, userAgent, extIdOpt).map(extId => Ok(extId.id))
    } catch {
      case ex: play.api.libs.json.JsResultException => Future.successful(BadRequest("invalid/incomplete json"))
      case ex: AddressException => Future.successful(BadRequest("invalid email address"))
    }

  }

}
