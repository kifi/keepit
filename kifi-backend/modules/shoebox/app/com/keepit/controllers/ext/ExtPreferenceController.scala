package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.classify.{ DomainRepo, Domain, DomainStates }
import com.keepit.commanders.UserCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.RatherInsecureDESCrypt
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.common.mail.ElectronicMailCategory
import com.keepit.common.net.URI
import com.keepit.social.BasicUser
import com.keepit.model._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.functional.syntax._
import play.api.libs.json.{ __, JsNumber, Json }

import scala.concurrent.Future
import scala.math.{ max, min }

class ExtPreferenceController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  urlPatternRepo: URLPatternRepo,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  notifyPreferenceRepo: UserNotifyPreferenceRepo,
  domainRepo: DomainRepo,
  userToDomainRepo: UserToDomainRepo,
  userCommander: UserCommander)
    extends UserActions with ShoeboxServiceController {

  private case class UserPrefs(
    lookHereMode: Boolean,
    enterToSend: Boolean,
    maxResults: Int,
    showExtMsgIntro: Boolean,
    messagingEmails: Boolean)

  private implicit val userPrefsFormat = (
    (__ \ 'lookHereMode).write[Boolean] and
    (__ \ 'enterToSend).write[Boolean] and
    (__ \ 'maxResults).write[Int] and
    (__ \ 'showExtMsgIntro).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity)) and
    (__ \ 'messagingEmails).writeNullable[Boolean].contramap[Boolean](Some(_).filter(identity))
  )(unlift(UserPrefs.unapply))

  private val crypt = new RatherInsecureDESCrypt
  private val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  def getRules() = UserAction { request =>
    db.readOnlyReplica { implicit s =>
      Ok(Json.obj(
        "slider_rules" -> Json.obj("version" -> "hy0e5ijs", "rules" -> Json.obj("url" -> 1, "shown" -> 1)), // ignored as of extension 3.2.11
        "url_patterns" -> urlPatternRepo.getActivePatterns()))
    }
  }

  def setLookHereMode(on: Boolean) = UserAction { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, UserValues.lookHereMode.name, on))
    Ok(JsNumber(0))
  }

  def setEnterToSend(enterToSend: Boolean) = UserAction { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, UserValues.enterToSend.name, enterToSend))
    Ok(JsNumber(0))
  }

  def setMaxResults(n: Int) = UserAction { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, UserValues.maxResults.name, min(max(0, n), 3)))
    Ok(JsNumber(0))
  }

  def setShowExtMsgIntro(show: Boolean) = UserAction { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, UserValues.showExtMsgIntro.name, show))
    Ok(JsNumber(0))
  }

  // Note: can delete this action after 22 Feb 2015
  def setShowLibraryIntro(show: Boolean) = UserAction { request =>
    db.readWrite(implicit s => userValueRepo.setValue(request.user.id.get, UserValueName.EXT_SHOW_LIBRARY_INTRO, show))
    NoContent
  }

  def setEmailNotifyPreference(kind: ElectronicMailCategory, send: Boolean) = UserAction { request =>
    db.readWrite(implicit s => notifyPreferenceRepo.setNotifyPreference(request.user.id.get, kind, send))
    Ok(JsNumber(0))
  }

  def getPrefs(version: Int) = UserAction.async { request =>
    val ip = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val encryptedIp: String = scala.util.Try(crypt.crypt(ipkey, ip)).getOrElse("")
    val userId = request.user.id.get
    userCommander.setLastUserActive(userId) // The extension doesn't display Delighted surveys for the moment, so we
    // don't need to wait for that Future to complete before we move on
    loadUserPrefs(userId, request.experiments) map { prefs =>
      if (version == 1) {
        Ok(Json.arr("prefs", prefs, encryptedIp))
      } else {
        Ok(Json.obj(
          "user" -> BasicUser.fromUser(request.user),
          "prefs" -> prefs,
          "eip" -> encryptedIp))
      }
    }
  }

  def setKeeperPositionOnSite() = UserAction(parse.tolerantJson) { request =>
    val pos = request.body \ "pos"
    val host = (request.body \ "host").as[String]

    db.readWrite { implicit s =>
      val domain = domainRepo.get(host, excludeState = None) match {
        case Some(d) if d.state != DomainStates.ACTIVE => domainRepo.save(d.withState(DomainStates.ACTIVE))
        case Some(d) => d
        case None => domainRepo.save(Domain(hostname = host))
      }
      userToDomainRepo.get(request.user.id.get, domain.id.get, UserToDomainKinds.KEEPER_POSITION) match {
        case Some(p) if p.state != UserToDomainStates.ACTIVE || p.value.get != pos =>
          userToDomainRepo.save(p.withState(UserToDomainStates.ACTIVE).withValue(Some(pos)))
        case Some(p) => p
        case None =>
          userToDomainRepo.save(UserToDomain(None, request.user.id.get, domain.id.get, UserToDomainKinds.KEEPER_POSITION, Some(pos)))
      }
    }
    Ok
  }

  def setKeeperHiddenOnSite() = UserAction(parse.tolerantJson) { request =>
    val json = request.body
    val host: String = URI.parse((json \ "url").as[String]).get.host.get.name
    val suppress: Boolean = (json \ "suppress").as[Boolean]
    db.readWrite(attempts = 3) { implicit s =>
      val domain = domainRepo.get(host, excludeState = None) match {
        case Some(d) if d.isActive => d
        case Some(d) => domainRepo.save(d.withState(DomainStates.ACTIVE))
        case None => domainRepo.save(Domain(hostname = host))
      }
      userToDomainRepo.get(request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW) match {
        case Some(utd) if (utd.isActive != suppress) =>
          userToDomainRepo.save(utd.withState(if (suppress) UserToDomainStates.ACTIVE else UserToDomainStates.INACTIVE))
        case Some(utd) => utd
        case None =>
          userToDomainRepo.save(UserToDomain(None, request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW, None))
      }
    }

    Ok(Json.obj("host" -> host, "suppressed" -> suppress))
  }

  private def loadUserPrefs(userId: Id[User], experiments: Set[ExperimentType]): Future[UserPrefs] = {
    val userValsFuture = db.readOnlyMasterAsync { implicit s => userValueRepo.getValues(userId, UserValues.ExtUserInitPrefs: _*) }
    val messagingEmailsFuture = db.readOnlyReplicaAsync { implicit s => notifyPreferenceRepo.canNotify(userId, NotificationCategory.User.MESSAGE) }
    for {
      userVals <- userValsFuture
      messagingEmails <- messagingEmailsFuture
    } yield {
      UserPrefs(
        lookHereMode = UserValues.lookHereMode.parseFromMap(userVals),
        enterToSend = UserValues.enterToSend.parseFromMap(userVals),
        maxResults = UserValues.maxResults.parseFromMap(userVals),
        showExtMsgIntro = UserValues.showExtMsgIntro.parseFromMap(userVals),
        messagingEmails = messagingEmails)
    }
  }
}
