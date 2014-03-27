package com.keepit.controllers.ext

import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.commanders.AuthCommander
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.crypto.SimpleDESCrypt
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.net._
import com.keepit.common.social.{FacebookSocialGraph, LinkedInSocialGraph}
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.model.KifiInstallation
import com.keepit.social.{SecureSocialClientIds, BasicUser}

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc.Action

import securesocial.core.{OAuth2Info, Registry}

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

  private val crypt = new SimpleDESCrypt
  private val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  def start = JsonAction.authenticatedParseJson { implicit request =>
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
          val inst = installationRepo.save(KifiInstallation(userId = userId, userAgent = userAgent, version = version))
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
          val installedExtensions = db.readOnly { implicit session => installationRepo.all(user.id.get, Some(KifiInstallationStates.INACTIVE)).length }
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

  def accessTokenLogin(providerName: String) = Action.async(parse.json) { implicit request =>
    Registry.providers.get(providerName) map { provider =>
      (providerName match {
        case "linkedin" => linkedIn.vetJsAccessToken(provider, request.body)
        case "facebook" => facebook.vetJsAccessToken(provider, request.body)
        case _ => Future.failed(new IllegalArgumentException(providerName))
      }) map { identityId =>
        authCommander.loginWithTrustedSocialIdentity(identityId)
      } recover { case t =>
        println(s"-----------EAC: ${t.getClass} ${t.getMessage}")
        t.printStackTrace
        BadRequest(Json.obj("error" -> "problem_vetting_token"))
      }
    } getOrElse Future.successful(BadRequest(Json.obj("error" -> "no_such_provider")))
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

  def whois = JsonAction.authenticated { request =>
    val user = db.readOnly(implicit s => userRepo.get(request.userId))
    Ok(Json.obj("externalUserId" -> user.externalId.toString))
  }
}
