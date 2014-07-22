package com.keepit.controllers.ext

import scala.util.Failure

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.commanders.AuthCommander
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.crypto.RatherInsecureDESCrypt
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.net._
import com.keepit.common.social.{ FacebookSocialGraph, LinkedInSocialGraph }
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.model.KifiInstallation
import com.keepit.social.{ BasicUser, SecureSocialClientIds }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc.Action

import securesocial.core.{ OAuth2Provider, Registry }

class ExtAuthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  airbrake: AirbrakeNotifier,
  authCommander: AuthCommander,
  userRepo: UserRepo,
  installationRepo: KifiInstallationRepo,
  urlPatternRepo: URLPatternRepo,
  sliderRuleRepo: SliderRuleRepo,
  socialUserRepo: SocialUserInfoRepo,
  kifiInstallationCookie: KifiInstallationCookie,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  secureSocialClientIds: SecureSocialClientIds,
  facebook: FacebookSocialGraph,
  linkedIn: LinkedInSocialGraph)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  private val crypt = new RatherInsecureDESCrypt
  private val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  def start = JsonAction.authenticatedParseJson { implicit request =>
    val userId = request.userId
    val identity = request.identity
    log.info(s"start id: $userId, social id: ${identity.identityId}")

    val json = request.body
    val (userAgent, version, installationIdOpt) =
      (UserAgent.fromString(request.headers.get("user-agent").getOrElse("")),
        KifiExtVersion((json \ "version").as[String]),
        (json \ "installation").asOpt[String].flatMap { id =>
          val kiId = ExternalId.asOpt[KifiInstallation](id)
          if (kiId.isEmpty) {
            // They sent an invalid id. Bug on client side?
            airbrake.notify(AirbrakeError(
              method = Some(request.method.toUpperCase),
              userId = request.user.id,
              userName = Some(request.user.fullName),
              url = Some(request.path),
              message = Some(s"""Invalid ExternalId passed in "$id" for userId $userId""")))
          }
          kiId
        })
    log.info(s"start details: $userAgent, $version, $installationIdOpt")

    val (user, installation, sliderRuleGroup, urlPatterns, isInstall, isUpdate, notAuthed) = db.readWrite { implicit s =>
      val user: User = userRepo.get(userId)
      val notAuthed = socialUserRepo.getNotAuthorizedByUser(userId).map(_.networkType.name)
      val (installation, isInstall, isUpdate): (KifiInstallation, Boolean, Boolean) = installationIdOpt flatMap { id =>
        installationRepo.getOpt(userId, id)
      } match {
        case None =>
          val inst = installationRepo.save(KifiInstallation(userId = userId, userAgent = userAgent, version = version, platform = KifiInstallationPlatform.Extension))
          (inst, true, false)
        case Some(install) if install.version != version || install.userAgent != userAgent || !install.isActive =>
          (installationRepo.save(install.withUserAgent(userAgent).withVersion(version).withState(KifiInstallationStates.ACTIVE)), false, true)
        case Some(install) =>
          (installationRepo.save(install), false, false)
      }
      val sliderRuleGroup: SliderRuleGroup = sliderRuleRepo.getGroup("default")
      val urlPatterns: Seq[String] = urlPatternRepo.getActivePatterns
      (user, installation, sliderRuleGroup, urlPatterns, isInstall, isUpdate, notAuthed)
    }

    if (isUpdate || isInstall) {
      SafeFuture {
        val contextBuilder = heimdalContextBuilder()
        contextBuilder.addRequestInfo(request)
        contextBuilder += ("extensionVersion", installation.version.toString)
        contextBuilder += ("kifiInstallationId", installation.id.get.toString)

        if (isInstall) {
          contextBuilder += ("action", "installedExtension")
          val installedExtensions = db.readOnlyMaster { implicit session => installationRepo.all(user.id.get, Some(KifiInstallationStates.INACTIVE)).length }
          contextBuilder += ("installation", installedExtensions)
          heimdal.setUserProperties(userId, "installedExtensions" -> ContextDoubleData(installedExtensions))
        } else
          contextBuilder += ("action", "updatedExtension")

        heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.JOINED, installation.updatedAt))
        heimdal.setUserProperties(userId, "latestExtension" -> ContextStringData(installation.version.toString))
      }
    }

    val ip = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val encryptedIp: String = crypt.crypt(ipkey, ip)

    Ok(Json.obj(
      "joined" -> user.createdAt.toLocalDate,
      "user" -> BasicUser.fromUser(user),
      "installationId" -> installation.externalId.id,
      "experiments" -> request.experiments.map(_.value),
      "rules" -> sliderRuleGroup.compactJson,
      "patterns" -> urlPatterns,
      "eip" -> encryptedIp,
      "notAuthed" -> notAuthed
    )).withCookies(kifiInstallationCookie.encodeAsCookie(Some(installation.externalId)))
  }

  def jsTokenLogin(providerName: String) = Action(parse.json) { implicit request =>
    Registry.providers.get(providerName) map (_.asInstanceOf[OAuth2Provider].settings) map { settings =>
      ((providerName match {
        case "linkedin" => linkedIn.vetJsAccessToken(settings, request.body)
        case "facebook" => facebook.vetJsAccessToken(settings, request.body)
        case _ => Failure(new IllegalArgumentException("Provider: " + providerName))
      }) map { identityId =>
        authCommander.loginWithTrustedSocialIdentity(identityId)
      } recover {
        case t =>
          airbrake.notify(AirbrakeError.incoming(request, t, "could not log in"))
          BadRequest(Json.obj("error" -> "problem_vetting_token"))
      }).get
    } getOrElse BadRequest(Json.obj("error" -> "no_such_provider"))
  }

  // where SecureSocial sends users if it can't figure out the right place (see securesocial.conf)
  def welcome = JsonAction.authenticated { implicit request =>
    log.debug("in welcome. with user : [ %s ]".format(request.identity))
    Redirect("/")
  }

  // TODO: Fix logOut. ActionAuthenticator currently sets a new session cookie after this action clears it.
  def logOut = JsonAction.authenticated { implicit request =>
    Ok(views.html.logOut(Some(request.identity), secureSocialClientIds.facebook)).withNewSession
  }
}
