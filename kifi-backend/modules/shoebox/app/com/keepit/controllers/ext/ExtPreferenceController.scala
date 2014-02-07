package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.classify.{DomainRepo, Domain, DomainStates}
import com.keepit.common.controller.{BrowserExtensionController, ShoeboxServiceController, WebsiteController, ActionAuthenticator}
import com.keepit.common.crypto.SimpleDESCrypt
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.mail.ElectronicMailCategory
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.model._
import com.keepit.normalizer.NormalizationService
import com.keepit.social.BasicUser

import play.api.libs.functional.syntax._
import play.api.libs.json.{__, JsNumber, JsObject, Json}

import scala.math.{max, min}

class ExtPreferenceController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  normalizedURIRepo: NormalizedURIRepo,
  sliderRuleRepo: SliderRuleRepo,
  urlPatternRepo: URLPatternRepo,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  notifyPreferenceRepo: UserNotifyPreferenceRepo,
  domainRepo: DomainRepo,
  userToDomainRepo: UserToDomainRepo,
  normalizationService: NormalizationService)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  private case class UserPrefs(
    enterToSend: Boolean,
    maxResults: Int,
    showKeeperIntro: Boolean,
    showSearchIntro: Boolean,
    showFindFriends: Boolean,
    messagingEmails: Boolean)

  private implicit val userPrefsFormat = (
      (__ \ 'enterToSend).write[Boolean] and
      (__ \ 'maxResults).write[Int] and
      (__ \ 'showKeeperIntro).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
      (__ \ 'showSearchIntro).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
      (__ \ 'showFindFriends).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
      (__ \ 'messagingEmails).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity))
    )(unlift(UserPrefs.unapply))

  private val crypt = new SimpleDESCrypt
  private val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  def normalize(url: String) = JsonAction.authenticated { request =>
    val json = db.readOnly { implicit session => Json.arr(normalizationService.normalize(url)) }
    Ok(json)
  }

  def getRules(version: String) = JsonAction.authenticated { request =>
    db.readOnly { implicit s =>
      val group = sliderRuleRepo.getGroup("default")
      if (version != group.version) {
        Ok(Json.obj("slider_rules" -> group.compactJson, "url_patterns" -> urlPatternRepo.getActivePatterns()))
      } else {
        Ok(Json.obj())
      }
    }
  }

  def setEnterToSend(enterToSend: Boolean) = JsonAction.authenticated { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, "enter_to_send", enterToSend.toString))
    Ok(JsNumber(0))
  }

  def setMaxResults(n: Int) = JsonAction.authenticated { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, "ext_max_results", min(max(1, n), 3).toString))
    Ok(JsNumber(0))
  }

  def setShowKeeperIntro(show: Boolean) = JsonAction.authenticated { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, "ext_show_keeper_intro", show.toString))
    Ok(JsNumber(0))
  }

  def setShowSearchIntro(show: Boolean) = JsonAction.authenticated { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, "ext_show_search_intro", show.toString))
    Ok(JsNumber(0))
  }

  def setShowFindFriends(show: Boolean) = JsonAction.authenticated { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, "ext_show_find_friends", show.toString))
    Ok(JsNumber(0))
  }

  def setEmailNotifyPreference(kind: ElectronicMailCategory, send: Boolean) = JsonAction.authenticated { request =>
    db.readWrite(implicit s => notifyPreferenceRepo.setNotifyPreference(request.user.id.get, kind, send))
    Ok(JsNumber(0))
  }

  def getPrefs() = JsonAction.authenticated { request =>
    val ip = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val encryptedIp: String = crypt.crypt(ipkey, ip)
    Ok(Json.arr("prefs", loadUserPrefs(request.user.id.get), encryptedIp))
  }

  def setKeeperPosition() = JsonAction.authenticatedParseJson { request =>
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
        enterToSend = userValueRepo.getValue(userId, "enter_to_send").map(_.toBoolean).getOrElse(true),
        maxResults = userValueRepo.getValue(userId, "ext_max_results").map(_.toInt).getOrElse(1),
        showKeeperIntro = userValueRepo.getValue(userId, "ext_show_keeper_intro").map(_.toBoolean).getOrElse(false),
        showSearchIntro = userValueRepo.getValue(userId, "ext_show_search_intro").map(_.toBoolean).getOrElse(false),
        showFindFriends = userValueRepo.getValue(userId, "ext_show_find_friends").map(_.toBoolean).getOrElse(false),
        messagingEmails = notifyPreferenceRepo.canNotify(userId, NotificationCategory.User.MESSAGE))
    }
  }
}
