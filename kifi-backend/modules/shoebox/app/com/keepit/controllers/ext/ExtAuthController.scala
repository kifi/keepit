package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.net._
import com.keepit.model._
import com.keepit.heimdal.{HeimdalServiceClient, EventContextBuilderFactory, UserEvent, EventType}
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

class ExtAuthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  airbrake: AirbrakeNotifier,
  userRepo: UserRepo,
  installationRepo: KifiInstallationRepo,
  urlPatternRepo: URLPatternRepo,
  sliderRuleRepo: SliderRuleRepo,
  kifiInstallationCookie: KifiInstallationCookie,
  userEventContextBuilder: EventContextBuilderFactory,
  heimdal: HeimdalServiceClient)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

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
         kiId match {
           case Some(_) =>
           case None =>
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
          (installationRepo.save(KifiInstallation(userId = userId, userAgent = userAgent, version = version)), true, true)
        case Some(install) if install.version != version || install.userAgent != userAgent || !install.isActive =>
          (installationRepo.save(install.withUserAgent(userAgent).withVersion(version).withState(KifiInstallationStates.ACTIVE)), false, true)
        case Some(install) =>
          (installationRepo.save(install), false, false)
      }
      val sliderRuleGroup: SliderRuleGroup = sliderRuleRepo.getGroup("default")
      val urlPatterns: Seq[String] = urlPatternRepo.getActivePatterns
      (user, installation, sliderRuleGroup, urlPatterns, firstTime, isUpgrade)
    }

    if (isUpgrade){
      SafeFuture{
        val contextBuilder = userEventContextBuilder(Some(request))
        contextBuilder += ("extVersion", installation.version.toString)
        contextBuilder += ("firstTime", firstTime)
        heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, EventType("extension_install")))
      }
    }

    Ok(Json.obj(
      "name" -> s"${user.firstName} ${user.lastName}",
      "userId" -> user.externalId.id,
      "installationId" -> installation.externalId.id,
      "experiments" -> request.experiments.map(_.value),
      "rules" -> sliderRuleGroup.compactJson,
      "patterns" -> urlPatterns
    )).withCookies(kifiInstallationCookie.encodeAsCookie(Some(installation.externalId)))
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
