package com.keepit.controllers.ext

import com.keepit.classify.{Domain, DomainRepo, DomainStates}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URI
import com.keepit.model._

import play.api.Play.current
import play.api.libs.json.Json

import com.google.inject.{Inject, Singleton}

@Singleton
class ExtUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  domainRepo: DomainRepo,
  userToDomainRepo: UserToDomainRepo)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def suppressSliderForSite() = AuthenticatedJsonToJsonAction { request =>
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
