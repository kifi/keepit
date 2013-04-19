package com.keepit.controllers.ext

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo, DomainStates}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.serializer.BasicUserSerializer
import com.keepit.common.social._
import com.keepit.search.graph.URIGraph
import com.keepit.search.Lang
import com.keepit.search.MainSearcherFactory
import com.keepit.common.mail._
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}

import scala.concurrent.Await
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.libs.json.{Json, JsBoolean}
import scala.concurrent.duration._
import views.html

import com.google.inject.{Inject, Singleton}

@Singleton
class ExtUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  domainRepo: DomainRepo,
  userToDomainRepo: UserToDomainRepo,
  socialConnectionRepo: SocialConnectionRepo,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo)
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
          userToDomainRepo.save(UserToDomain(None, request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW))
      }
    }

    Ok(Json.obj("host" -> host, "suppressed" -> suppress))
  }

  def getSocialConnections() = AuthenticatedJsonAction { authRequest =>
    val socialConnections = db.readOnly {implicit s =>
      socialConnectionRepo.getFortyTwoUserConnections(authRequest.userId).map(uid => basicUserRepo.load(uid)).toSeq
    }

    Ok(Json.obj("friends" -> socialConnections.map(sc => BasicUserSerializer.basicUserSerializer.writes(sc))))
  }
}
