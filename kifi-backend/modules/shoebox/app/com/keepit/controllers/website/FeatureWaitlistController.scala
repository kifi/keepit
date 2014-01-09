package com.keepit.controllers.website

import com.keepit.common.controller.{ActionAuthenticator, WebsiteController}
import com.keepit.commanders.FeatureWaitlistCommander
import com.keepit.model.FeatureWaitlistEntry
import com.keepit.common.db.ExternalId

import com.google.inject.Inject

import play.api.mvc.Action

class FeatureWaitlistController @Inject() (actionAuthenticator: ActionAuthenticator, commander: FeatureWaitlistCommander) extends WebsiteController(actionAuthenticator) {

  def waitList() = Action(parse.tolerantJson) { request =>
    val email = (request.body \ "email").as[String]
    val feature = (request.body \ "feature").as[String]
    val extIdOpt = (request.body \ "extId").asOpt[String].map(ExternalId[FeatureWaitlistEntry](_))
    val userAgent = request.headers.get("User-Agent").getOrElse("")
    Ok(commander.waitList(email, feature, userAgent, extIdOpt).id)
  }

}
