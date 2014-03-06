package com.keepit.controllers.ext

import com.keepit.classify.{Domain, DomainRepo, DomainStates}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.commanders.{UserCommander, BasicSocialUser}

import play.api.Play.current
import play.api.http.ContentTypes.JSON
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsObject, Json}

import com.google.inject.Inject
import com.keepit.common.net.URI
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.BasicUser
import com.keepit.common.analytics.{Event, EventFamilies, Events}

class ExtUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  domainRepo: DomainRepo,
  userToDomainRepo: UserToDomainRepo,
  userCommander: UserCommander)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getLoggedIn() = JsonAction(allowPending = true)(authenticatedAction = { request =>
    if (request.user.state == UserStates.ACTIVE) {
      Ok("true").as(JSON)
    } else {
      Forbidden("0").as(JSON) // TODO: change to Ok("false") once all extensions are at 2.6.37 or later
    }
  }, unauthenticatedAction = { request =>
    Forbidden("0").as(JSON) // TODO: change to Ok("false") once all extensions are at 2.6.37 or later
  })

  def getFriends() = JsonAction.authenticated { request =>
    Ok(Json.toJson(userCommander.getFriends(request.user, request.experiments)))
  }

  def suppressSliderForSite() = JsonAction.authenticatedParseJson { request =>
    val json = request.body
    val host: String = URI.parse((json \ "url").as[String]).get.host.get.name
    val suppress: Boolean = (json \ "suppress").as[Boolean]
    db.readWrite(attempts = 3) { implicit s =>
      val domain = domainRepo.get(host, excludeState = None) match {
        case Some(d) if d.isActive => d
        case Some(d) => domainRepo.save(d.withState(DomainStates.ACTIVE))
        case None => domainRepo.save(Domain(hostname = host))
      }
      userToDomainRepo.get(request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW, excludeState = None) match {
        case Some(utd) if (utd.isActive != suppress) =>
          userToDomainRepo.save(utd.withState(if (suppress) UserToDomainStates.ACTIVE else UserToDomainStates.INACTIVE))
        case Some(utd) => utd
        case None =>
          userToDomainRepo.save(UserToDomain(None, request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW, None))
      }
    }

    Ok(Json.obj("host" -> host, "suppressed" -> suppress))
  }

}
