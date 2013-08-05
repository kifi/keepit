package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{BrowserExtensionController, ShoeboxServiceController, WebsiteController, ActionAuthenticator}
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.net.URINormalizer
import play.api.libs.json.{JsObject, Json}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.social.BasicUser
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.classify.{DomainRepo, Domain, DomainStates}

class ExtPreferenceController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  normalizedURIRepo: NormalizedURIRepo,
  sliderRuleRepo: SliderRuleRepo,
  urlPatternRepo: URLPatternRepo,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  domainRepo: DomainRepo,
  userToDomainRepo: UserToDomainRepo)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  private case class UserPrefs(enterToSend: Boolean)
  private implicit val userPrefsFormat = Json.format[UserPrefs]

  def normalize(url: String) = AuthenticatedJsonToJsonAction { request =>
    // Todo: upgrade to new normalization
    Ok(URINormalizer.normalize(url))
  }

  def getRules(version: String) = AuthenticatedJsonToJsonAction { request =>
    db.readOnly { implicit s =>
      val group = sliderRuleRepo.getGroup("default")
      if (version != group.version) {
        Ok(Json.obj("slider_rules" -> group.compactJson, "url_patterns" -> urlPatternRepo.getActivePatterns()))
      } else {
        Ok
      }
    }
  }

  def setEnterToSend(enterToSend: Boolean) = AuthenticatedJsonToJsonAction { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, "enter_to_send", enterToSend.toString))
    Ok(Json.arr("prefs", loadUserPrefs(request.user.id.get)))
  }

  def getPrefs() = AuthenticatedJsonToJsonAction { request =>
    Ok(Json.arr("prefs", loadUserPrefs(request.user.id.get)))
  }

  def setKeeperPosition() = AuthenticatedJsonToJsonAction { request =>
    val pos = request.body \ "pos"
    val host = (request.body \ "host").as[String]

    db.readWrite { implicit s =>
      val domain = domainRepo.get(host, excludeState = None) match {
        case Some(d) if d.state != DomainStates.ACTIVE => domainRepo.save(d.withState(DomainStates.ACTIVE))
        case Some(d) => d
        case None => domainRepo.save(Domain(hostname = host))
      }
      userToDomainRepo.get(request.user.id.get, domain.id.get, UserToDomainKinds.KEEPER_POSITION, excludeState = None) match {
        case Some(p) if p.state != UserToDomainStates.ACTIVE || p.value.get != pos =>
          userToDomainRepo.save(p.withState(UserToDomainStates.ACTIVE).withValue(Some(pos)))
        case Some(p) => p
        case None =>
          userToDomainRepo.save(UserToDomain(None, request.user.id.get, domain.id.get, UserToDomainKinds.KEEPER_POSITION, Some(pos)))
      }
    }
    Ok
  }

  private def loadUserPrefs(userId: Id[User]): UserPrefs = {
    db.readOnly { implicit s =>
      UserPrefs(
        enterToSend = userValueRepo.getValue(userId, "enter_to_send").map(_.toBoolean).getOrElse(true)
      )
    }
  }
}
