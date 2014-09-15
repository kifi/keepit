package com.keepit.controllers.ext

import scala.util.Failure

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.commanders.{ AuthCommander, LibraryCommander }
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.crypto.{ PublicIdConfiguration, RatherInsecureDESCrypt }
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.net.UserAgent
import com.keepit.common.social.{ FacebookSocialGraph, LinkedInSocialGraph }
import com.keepit.heimdal.{ ContextDoubleData, ContextStringData, HeimdalContextBuilderFactory, HeimdalServiceClient, UserEvent, UserEventTypes }
import com.keepit.model.{ KifiExtVersion, KifiInstallation, KifiInstallationPlatform, KifiInstallationRepo, KifiInstallationStates }
import com.keepit.model.{ Library, SliderRuleGroup, SliderRuleRepo, URLPatternRepo }
import com.keepit.social.BasicUser

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc.Action

import securesocial.core.{ Authenticator, Events, LogoutEvent, OAuth2Provider, Registry, SecureSocial, UserService }

class ExtAuthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  airbrake: AirbrakeNotifier,
  authCommander: AuthCommander,
  libraryCommander: LibraryCommander,
  installationRepo: KifiInstallationRepo,
  urlPatternRepo: URLPatternRepo,
  sliderRuleRepo: SliderRuleRepo,
  kifiInstallationCookie: KifiInstallationCookie,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  facebook: FacebookSocialGraph,
  linkedIn: LinkedInSocialGraph,
  implicit val publicIdConfig: PublicIdConfiguration)
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
              userId = Some(userId),
              userName = Some(request.user.fullName),
              url = Some(request.path),
              message = Some(s"""Invalid ExternalId passed in "$id" for userId $userId""")))
          }
          kiId
        })
    log.info(s"start details: $userAgent, $version, $installationIdOpt")

    val (libraries, installation, sliderRuleGroup, urlPatterns, isInstall, isUpdate) = db.readWrite { implicit s =>
      val libraries = libraryCommander.getMainAndSecretLibrariesForUser(userId)
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
      (libraries, installation, sliderRuleGroup, urlPatterns, isInstall, isUpdate)
    }

    if (isUpdate || isInstall) {
      SafeFuture {
        val contextBuilder = heimdalContextBuilder()
        contextBuilder.addRequestInfo(request)
        contextBuilder += ("extensionVersion", installation.version.toString)
        contextBuilder += ("kifiInstallationId", installation.id.get.toString)

        if (isInstall) {
          contextBuilder += ("action", "installedExtension")
          val installedExtensions = db.readOnlyMaster { implicit session => installationRepo.all(userId, Some(KifiInstallationStates.INACTIVE)).length }
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
      "user" -> BasicUser.fromUser(request.user),
      "libraryIds" -> Seq(libraries._1.id.get, libraries._2.id.get).map(Library.publicId),
      "installationId" -> installation.externalId.id,
      "experiments" -> request.experiments.map(_.value),
      "rules" -> sliderRuleGroup.compactJson,
      "patterns" -> urlPatterns,
      "eip" -> encryptedIp
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

  def logOut = Action { implicit request => // code mostly copied from LoginPage.logout
    val user = for (
      authenticator <- SecureSocial.authenticatorFromRequest;
      user <- UserService.find(authenticator.identityId)
    ) yield {
      Authenticator.delete(authenticator.id)
      user
    }
    val result = NoContent.discardingCookies(Authenticator.discardingCookie)
    user match {
      case Some(u) => result.withSession(Events.fire(new LogoutEvent(u)).getOrElse(request.session))
      case None => result
    }
  }

}
