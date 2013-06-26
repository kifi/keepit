package com.keepit.controllers.ext

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck._
import com.keepit.common.net._
import com.keepit.model._

import play.api.libs.json.Json
import play.api.mvc.Action
import securesocial.core._

@Singleton
class ExtAuthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  healthcheckPlugin: HealthcheckPlugin,
  userRepo: UserRepo,
  installationRepo: KifiInstallationRepo,
  urlPatternRepo: URLPatternRepo,
  sliderRuleRepo: SliderRuleRepo,
  userExperimentRepo: UserExperimentRepo,
  kifiInstallationCookie: KifiInstallationCookie)
  extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  // Some of this is copied from ProviderController in SecureSocial
  // We might be able to do this better but I'm just trying to get it working for now
  def mobileAuth(providerName: String) = Action(parse.json) { implicit request =>
    // e.g. { "accessToken": "..." }
    val oauth2Info = Json.fromJson(request.body)(Json.reads[OAuth2Info]).asOpt
    val provider = Registry.providers.get(providerName).get
    val authMethod = provider.authMethod
    val filledUser = provider.fillProfile(
      SocialUser(UserId("", providerName), "", "", "", None, None, authMethod, oAuth2Info = oauth2Info))
    UserService.find(filledUser.id) map { user =>
      val withSession = Events.fire(new LoginEvent(user)).getOrElse(session)
      Authenticator.create(user) match {
        case Right(authenticator) =>
          Ok(Json.obj("sessionId" -> authenticator.id)).withSession(
              withSession - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
            .withCookies(authenticator.toCookie)
        case Left(error) => throw error
      }
    } getOrElse {
      NotFound(Json.obj("error" -> "user not found"))
    }
  }

  def start = AuthenticatedJsonToJsonAction { implicit request =>
    val userId = request.userId
    val identity = request.identity
    log.info(s"start id: $userId, social id: ${identity.id}")

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
        case Some(install) if install.version != version || install.userAgent != userAgent || !install.isActive =>
          installationRepo.save(install.withUserAgent(userAgent).withVersion(version).withState(KifiInstallationStates.ACTIVE))
        case Some(install) =>
          install
      }
      val experiments: Set[String] = userExperimentRepo.getUserExperiments(user.id.get).map(_.value)
      val sliderRuleGroup: SliderRuleGroup = sliderRuleRepo.getGroup("default")
      val urlPatterns: Seq[String] = urlPatternRepo.getActivePatterns
      (user, installation, experiments, sliderRuleGroup, urlPatterns)
    }

    Ok(Json.obj(
      "name" -> identity.fullName,
      "userId" -> user.externalId.id,
      "installationId" -> installation.externalId.id,
      "experiments" -> experiments,
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
