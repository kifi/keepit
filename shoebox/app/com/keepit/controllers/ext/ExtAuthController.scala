package com.keepit.controllers.ext

import play.api.mvc.{Action, Controller}
import play.api.i18n.Messages
import securesocial.core._
import play.api.{Play, Logger}
import Play.current
import play.api.mvc.{CookieBaker, Session}
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.json.Json
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.db._
import com.keepit.common.social.{SocialId, SocialNetworks}
import com.keepit.common.net._
import com.keepit.model._
import com.keepit.common.healthcheck._
import com.keepit.common.db.slick._

import com.google.inject.{Inject, Singleton}

@Singleton
class ExtAuthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  healthcheckPlugin: HealthcheckPlugin,
  userRepo: UserRepo,
  installationRepo: KifiInstallationRepo,
  urlPatternRepo: URLPatternRepo,
  sliderRuleRepo: SliderRuleRepo,
  userExperimentRepo: UserExperimentRepo)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {
  def start = AuthenticatedJsonToJsonAction { implicit request =>
    val userId = request.userId
    val identity = request.identity
    log.info(s"start id: $userId, facebook id: ${identity.id}")

    val json = request.body
    val (userAgent, version, installationIdOpt) =
      (UserAgent((json \ "agent").as[String]),
       KifiVersion((json \ "version").as[String]),
       (json \ "installation").asOpt[String].flatMap { id =>
         val kiId = ExternalId.asOpt[KifiInstallation](id)
         kiId match {
           case Some(_) =>
           case None =>
             // They sent an invalid id. Bug on client side?
             healthcheckPlugin.addError(HealthcheckError(
               method = Some(request.method.toUpperCase()),
               path = Some(request.path),
               callType = Healthcheck.API,
               errorMessage = Some("Invalid ExternalId passed in \"%s\" for userId %s".format(id, userId))))
         }
         kiId
       })
    log.info(s"start details: $userAgent, $version, $installationIdOpt")

    val (user, installation, experiments, sliderRuleGroup, urlPatterns) = db.readWrite{implicit s =>
      val user: User = userRepo.get(userId)
      val installation: KifiInstallation = installationIdOpt flatMap { id =>
        installationRepo.getOpt(userId, id)
      } match {
        case None =>
          installationRepo.save(KifiInstallation(userId = userId, userAgent = userAgent, version = version))
        case Some(install) if install.version != version || install.userAgent != userAgent =>
          installationRepo.save(install.withUserAgent(userAgent).withVersion(version))
        case Some(install) =>
          install
      }
      val experiments: Seq[String] = userExperimentRepo.getUserExperiments(user.id.get).map(_.value)
      val sliderRuleGroup: SliderRuleGroup = sliderRuleRepo.getGroup("default")
      val urlPatterns: Seq[String] = urlPatternRepo.getActivePatterns
      (user, installation, experiments, sliderRuleGroup, urlPatterns)
    }

    Ok(Json.obj(
      "avatarUrl" -> identity.avatarUrl.get,
      "name" -> identity.fullName,
      "facebookId" -> identity.id.id,
      "provider" -> identity.id.providerId,
      "userId" -> user.externalId.id,
      "installationId" -> installation.externalId.id,
      "experiments" -> experiments,
      "rules" -> sliderRuleGroup.compactJson,
      "patterns" -> urlPatterns))
    .withCookies(KifiInstallationCookie.encodeAsCookie(Some(installation.externalId)))
  }

  // where SecureSocial sends users if it can't figure out the right place (see securesocial.conf)
  def welcome = AuthenticatedJsonAction { implicit request =>
    log.debug("in welcome. with user : [ %s ]".format(request.identity))
    Redirect(com.keepit.controllers.website.routes.HomeController.home())
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
