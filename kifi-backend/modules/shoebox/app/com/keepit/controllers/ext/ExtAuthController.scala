package com.keepit.controllers.ext

import com.keepit.common.service.IpAddress

import scala.util.Failure

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.commanders._
import com.keepit.common.controller.FortyTwoCookies.KifiInstallationCookie
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicIdConfiguration, RatherInsecureDESCrypt }
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.net.UserAgent
import com.keepit.common.social.{ TwitterSocialGraph, TwitterSocialGraphImpl, FacebookSocialGraph, LinkedInSocialGraph }
import com.keepit.heimdal.{ ContextDoubleData, ContextStringData, HeimdalContextBuilderFactory, HeimdalServiceClient, UserEvent, UserEventTypes }
import com.keepit.model._
import com.keepit.social.BasicUser

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import securesocial.core.{ Authenticator, Events, LogoutEvent, OAuth2Provider, Registry, SecureSocial, UserService }
import KifiSession._

class ExtAuthController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  airbrake: AirbrakeNotifier,
  authCommander: AuthCommander,
  libraryInfoCommander: LibraryInfoCommander,
  installationRepo: KifiInstallationRepo,
  orgInfoCommander: OrganizationInfoCommander,
  orgMembershipCommander: OrganizationMembershipCommander,
  urlPatternRepo: URLPatternRepo,
  experimentCommander: LocalUserExperimentCommander,
  kifiInstallationCookie: KifiInstallationCookie,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  facebook: FacebookSocialGraph,
  linkedIn: LinkedInSocialGraph,
  twitter: TwitterSocialGraph,
  typeaheadCommander: TypeaheadCommander,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  private val crypt = new RatherInsecureDESCrypt
  private val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  def start = UserAction(parse.tolerantJson) { implicit request =>
    val userId = request.userId
    val user = request.user

    user.state match {
      case UserStates.INACTIVE | UserStates.BLOCKED =>
        Redirect("/logout") // This won't work when we stop blinding accepting redirects here
      case _ =>
        val json = request.body
        val (userAgent, version, installationIdOpt) =
          (UserAgent(request.headers.get("user-agent").getOrElse("")),
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

        val (libraries, organizations, installation, urlPatterns, isInstall, isUpdate) = db.readWrite { implicit s =>
          val libraries = libraryInfoCommander.getMainAndSecretLibrariesForUser(userId)
          val orgIds = orgMembershipCommander.getAllOrganizationsForUser(userId)
          val basicOrgs = orgInfoCommander.getBasicOrganizationViewsHelper(orgIds.toSet, Some(userId), authTokenOpt = None).values.toSeq
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
          val urlPatterns: Seq[String] = urlPatternRepo.getActivePatterns
          (libraries, basicOrgs, installation, urlPatterns, isInstall, isUpdate)
        }

        if (isUpdate || isInstall) {
          SafeFuture {
            if (version >= KifiExtVersion(3, 3, 18) && !request.experiments.contains(UserExperimentType.VISITED)) {
              experimentCommander.addExperimentForUser(userId, UserExperimentType.VISITED)
            }

            val contextBuilder = heimdalContextBuilder()
            contextBuilder.addRequestInfo(request)
            contextBuilder += ("extensionVersion", installation.version.toString)
            contextBuilder += ("kifiInstallationId", installation.id.get.toString)

            if (isInstall) {
              contextBuilder += ("action", "installedExtension")
              val installedExtensions = db.readOnlyMaster { implicit session => installationRepo.all(userId, Some(KifiInstallationStates.INACTIVE)).length }
              contextBuilder += ("installation", installedExtensions)
              heimdal.setUserProperties(userId, "installedExtensions" -> ContextDoubleData(installedExtensions))
              heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.JOINED, installation.updatedAt))
            } else {
              heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.UPDATED_EXTENSION, installation.updatedAt))
            }

            heimdal.setUserProperties(userId, "latestExtension" -> ContextStringData(installation.version.toString))
          }
        }

        val ip = IpAddress.fromRequest(request).ip
        val encryptedIp: String = crypt.crypt(ipkey, ip).trim

        SafeFuture { // Prefetch typeaheads on extension load.
          typeaheadCommander.searchAndSuggestKeepRecipients(userId, "", None, None, TypeaheadRequest.all)
        }

        Ok(Json.obj(
          "user" -> BasicUser.fromUser(request.user),
          "libraryIds" -> Seq(libraries._1.id.get, libraries._2.id.get).map(Library.publicId),
          "orgs" -> organizations,
          "installationId" -> installation.externalId.id,
          "experiments" -> request.experiments.map(_.value),
          "rules" -> Json.obj("version" -> "hy0e5ijs", "rules" -> Json.obj("url" -> 1, "shown" -> 1)), // ignored as of extension 3.2.11
          "patterns" -> urlPatterns,
          "eip" -> encryptedIp
        )).withCookies(kifiInstallationCookie.encodeAsCookie(Some(installation.externalId)))
    }
  }

  def jsTokenLogin(providerName: String) = MaybeUserAction(parse.json) { implicit request =>
    Registry.providers.get(providerName) map (_.asInstanceOf[OAuth2Provider].settings) map { settings =>
      ((providerName match {
        case "linkedin" => linkedIn.vetJsAccessToken(settings, request.body)
        case "facebook" => facebook.vetJsAccessToken(settings, request.body)
        case "twitter" => twitter.vetJsAccessToken(settings, request.body) // need validation from client; may consolidate with accessTokenLogin
        case _ => Failure(new IllegalArgumentException("Provider: " + providerName))
      }) map { identityId =>
        authCommander.loginWithTrustedSocialIdentity(identityId) // does not validate token against server
      } recover {
        case t =>
          airbrake.notify(AirbrakeError.incoming(request, t, "could not log in"))
          BadRequest(Json.obj("error" -> "problem_vetting_token"))
      }).get
    } getOrElse BadRequest(Json.obj("error" -> "no_such_provider"))
  }

  def getLoggedIn() = MaybeUserAction { implicit request =>
    request match {
      case ur: UserRequest[_] =>
        Ok(Json.toJson(ur.user.state == UserStates.ACTIVE))
      case _ =>
        Ok(Json.toJson(false))
    }
  }

  def logOut = MaybeUserAction { implicit request => // code mostly copied from LoginPage.logout
    val user = for (
      authenticator <- SecureSocial.authenticatorFromRequest;
      user <- authCommander.getUserIdentity(authenticator.identityId)
    ) yield {
      Authenticator.delete(authenticator.id)
      user
    }
    val result = NoContent.discardingCookies(Authenticator.discardingCookie)
    user match {
      case Some(u) => result.withSession(Events.fire(new LogoutEvent(u)).getOrElse(request.session).deleteUserId)
      case None => result
    }
  }

}
