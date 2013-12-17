package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.net._
import com.keepit.model._
import com.keepit.heimdal._
import com.keepit.social.BasicUser
import com.keepit.common.crypto.SimpleDESCrypt

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import com.keepit.model.KifiInstallation

class ExtAuthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  airbrake: AirbrakeNotifier,
  userRepo: UserRepo,
  installationRepo: KifiInstallationRepo,
  urlPatternRepo: URLPatternRepo,
  sliderRuleRepo: SliderRuleRepo,
  userExperimentRepo: UserExperimentRepo,
  kifiInstallationCookie: KifiInstallationCookie,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  private val crypt = new SimpleDESCrypt
  private val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  def start = AuthenticatedJsonToJsonAction { implicit request =>
    val userId = request.userId
    val identity = request.identity
    log.info(s"start id: $userId, social id: ${identity.identityId}")

    val json = request.body
    val (userAgent, version, installationIdOpt) =
      (UserAgent.fromString(request.headers.get("user-agent").getOrElse("")),
       KifiVersion((json \ "version").as[String]),
       (json \ "installation").asOpt[String].flatMap { id =>
         val kiId = ExternalId.asOpt[KifiInstallation](id)
         if (kiId.isEmpty) {
             // They sent an invalid id. Bug on client side?
             airbrake.notify(AirbrakeError(
               method = Some(request.method.toUpperCase()),
               url = Some(request.path),
               message = Some(s"""Invalid ExternalId passed in "$id" for userId $userId""")))
         }
         kiId
       })
    log.info(s"start details: $userAgent, $version, $installationIdOpt")

    val (user, installation, sliderRuleGroup, urlPatterns, firstTime, isUpgrade) = db.readWrite{implicit s =>
      val user: User = userRepo.get(userId)
      val (installation, firstTime, isUpgrade): (KifiInstallation, Boolean, Boolean) = installationIdOpt flatMap { id =>
        installationRepo.getOpt(userId, id)
      } match {
        case None =>
          val inst = installationRepo.save(KifiInstallation(userId = userId, userAgent = userAgent, version = version))
          (inst, true, true)
        case Some(install) if install.version != version || install.userAgent != userAgent || !install.isActive =>
          (installationRepo.save(install.withUserAgent(userAgent).withVersion(version).withState(KifiInstallationStates.ACTIVE)), false, true)
        case Some(install) =>
          (installationRepo.save(install), false, false)
      }
      val sliderRuleGroup: SliderRuleGroup = sliderRuleRepo.getGroup("default")
      val urlPatterns: Seq[String] = urlPatternRepo.getActivePatterns
      (user, installation, sliderRuleGroup, urlPatterns, firstTime, isUpgrade)
    }

    if (isUpgrade) {
      SafeFuture {
        val contextBuilder = heimdalContextBuilder()
        contextBuilder.addRequestInfo(request)
        contextBuilder += ("extensionVersion", installation.version.toString)
        contextBuilder += ("firstTime", firstTime)
        heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.EXTENSION_INSTALL))
      }
    }

    maybeEnableOrDisableInboxExperiment(userId, version, request.experiments)

    val ip = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val encryptedIp: String = crypt.crypt(ipkey, ip)

    Ok(Json.obj(
      "joined" -> user.createdAt.toLocalDate,
      "user" -> BasicUser.fromUser(user),
      "name" -> s"${user.firstName} ${user.lastName}",  // deprecated, remove after all extensions at 2.6.38 or later
      "userId" -> user.externalId.id,                   // deprecated, remove after all extensions at 2.6.38 or later
      "installationId" -> installation.externalId.id,
      "experiments" -> request.experiments.map(_.value),
      "rules" -> sliderRuleGroup.compactJson,
      "patterns" -> urlPatterns,
      "eip" -> encryptedIp
    )).withCookies(kifiInstallationCookie.encodeAsCookie(Some(installation.externalId)))
  }

  private def maybeEnableOrDisableInboxExperiment(userId: Id[User], version: KifiVersion, experiments: Set[ExperimentType]): Unit = {
    import ExperimentType.INBOX
    import UserExperimentStates.{ACTIVE, INACTIVE}
    if (version >= KifiVersion(2, 7, 0)) {
      if (!experiments.contains(INBOX)) {
        SafeFuture {
          db.readWrite { implicit session =>
            (userExperimentRepo.get(userId, INBOX, Some(ACTIVE)) match {
              case Some(ue) => Some(ue.withState(ACTIVE))
              case _ => Some(UserExperiment(userId = userId, experimentType = INBOX))
            }) map userExperimentRepo.save
          }
        }
      }
    } else if (experiments.contains(INBOX)) {
      SafeFuture {
        db.readWrite { implicit session =>
          userExperimentRepo.get(userId, INBOX) map { ue =>
            userExperimentRepo.save(ue.withState(INACTIVE))
          }
        }
      }
    }
  }

  // where SecureSocial sends users if it can't figure out the right place (see securesocial.conf)
  def welcome = AuthenticatedJsonAction { implicit request =>
    log.debug("in welcome. with user : [ %s ]".format(request.identity))
    Redirect("/")
  }

  // TODO: Fix logOut. ActionAuthenticator currently sets a new session cookie after this action clears it.
  def logOut = AuthenticatedHtmlAction { implicit request =>
    Ok(views.html.logOut(Some(request.identity))).withNewSession
  }

  def whois = AuthenticatedJsonAction { request =>
    val user = db.readOnly(implicit s => userRepo.get(request.userId))
    Ok(Json.obj("externalUserId" -> user.externalId.toString))
  }
}
