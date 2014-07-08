package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.AuthCommander
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, MobileController}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.UserAgent
import com.keepit.social.{SocialId, UserIdentity}
import com.keepit.controllers.core.{AuthController, AuthHelper}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.social.SocialNetworkType
import com.keepit.model._
import com.keepit.common.time.Clock
import com.keepit.social.providers.ProviderController

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsNumber, Json}
import play.api.mvc.{Action, Cookie, Session, SimpleResult}

import scala.util.{Failure, Success, Try}

import securesocial.core.{Authenticator, Events, IdentityId, IdentityProvider, LoginEvent}
import securesocial.core.{OAuth1Provider, OAuth2Info, Registry, SecureSocial, SocialUser, UserService}

class MobileAuthController @Inject() (
  airbrakeNotifier: AirbrakeNotifier,
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  clock: Clock,
  authCommander: AuthCommander,
  socialUserInfoRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  installationRepo: KifiInstallationRepo,
  authHelper:AuthHelper
) extends MobileController(actionAuthenticator) with ShoeboxServiceController with Logging {

  private implicit val readsOAuth2Info = Json.reads[OAuth2Info]

  def whatIsMyIp() = Action { request =>
    val ip = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    Ok(ip)
  }

  def registerIPhoneVersion() = JsonAction.authenticatedParseJson(allowPending = true) { request =>
    val json = request.body
    val installationIdOpt = (json \ "installation").asOpt[String].map(ExternalId[KifiInstallation](_))
    val version = KifiIPhoneVersion((json \ "version").as[String])
    val agent = UserAgent.fromString(request.headers.get("user-agent").getOrElse(""))
    registerMobileVersion(installationIdOpt, version, agent, request.userId, KifiInstallationPlatform.IPhone)
  }

  def registerAndroidVersion() = JsonAction.authenticatedParseJson(allowPending = true) { request =>
    val json = request.body
    val installationIdOpt = (json \ "installation").asOpt[String].map(ExternalId[KifiInstallation](_))
    val version = KifiAndroidVersion((json \ "version").as[String])
    val agent = UserAgent.fromString(request.headers.get("user-agent").getOrElse(""))
    registerMobileVersion(installationIdOpt, version, agent, request.userId, KifiInstallationPlatform.Android)
  }

  private def registerMobileVersion[T <: KifiVersion with Ordered[T]](installationIdOpt: Option[ExternalId[KifiInstallation]], version: T, agent: UserAgent, userId: Id[User], platform: KifiInstallationPlatform) = {
    val (installation, newInstallation) = installationIdOpt map {id =>
      db.readOnlyMaster { implicit s => installationRepo.get(id) }
    } match {
      case None =>
        db.readWrite { implicit s =>
          log.info(s"installation for user $userId does not exist, creating a new one")
          (installationRepo.save(KifiInstallation(userId = userId, userAgent = agent, version = version, platform = platform)), true)
        }
      case Some(existing) if !existing.isActive =>
        db.readWrite { implicit s =>
          log.info(s"activating installation $existing with latest version")
          (installationRepo.save(existing.withState(KifiInstallationStates.ACTIVE).withVersion(version)), false)
        }
      case Some(existing) if existing.userId != userId =>
        db.readWrite { implicit s =>
          log.info(s"installation $existing is not of user $userId, creating a new installation for user")
          (installationRepo.save(KifiInstallation(userId = userId, userAgent = agent, version = version, platform = platform)), true)
        }
      case Some(active) if active.version.asInstanceOf[T] < version =>
        db.readWrite { implicit s =>
          log.info(s"installation ${active.externalId} for user $userId exist but outdated, updating")
          (installationRepo.save(active.withVersion(version)), false)
        }
      case Some(active) if active.version.asInstanceOf[T] > version =>
        val message = s"TIME TRAVEL!!! installation ${active.externalId} for user $userId has version ${active.version} while we got from client an older version $version"
        db.readWrite { implicit s =>
          log.warn(message)
          (installationRepo.save(active.withVersion(version)), false)
        }
      case Some(active) =>
        log.info(s"installation ${active.externalId} for user $userId exist and version match")
        (active, false)
    }

    Ok(Json.obj(
      "installation" -> installation.externalId.toString,
      "newInstallation" -> newInstallation
    ))
  }

  def accessTokenSignup(providerName:String) = Action(parse.tolerantJson) { implicit request =>
    val resOpt = for {
      provider   <- Registry.providers.get(providerName)
      oauth2Info <- request.body.asOpt[OAuth2Info]
    } yield {
      Try {
        val socialUser = SocialUser(IdentityId("", provider.id), "", "", "", None, None, provider.authMethod, oAuth2Info = Some(oauth2Info))
        val filledSocialUser = provider.fillProfile(socialUser)
        filledSocialUser
      } match {
        case Success(filledUser) =>
          UserService.find(filledUser.identityId) match {
            case None =>
              val saved = UserService.save(UserIdentity(None, filledUser, allowSignup = false))
              log.info(s"[accessTokenSignup($providerName)] created social user: $saved")
              Authenticator.create(saved).fold(
                error => throw error,
                authenticator =>
                  Ok(Json.obj("code" -> "continue_signup", "sessionId" -> authenticator.id))
                    .withSession(session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                    .withCookies(authenticator.toCookie)
              )
            case Some(identity) => // social user exists
              db.readOnly(attempts = 2) { implicit s =>
                socialUserInfoRepo.getOpt(SocialId(identity.identityId.userId), SocialNetworkType(identity.identityId.providerId)) flatMap (_.userId)
              } match {
                case None => // kifi user does not exist
                  Authenticator.create(identity).fold(
                    error => throw error,
                    authenticator =>
                      Ok(Json.obj("code" -> "continue_signup", "sessionId" -> authenticator.id))
                        .withSession(session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                        .withCookies(authenticator.toCookie)
                  )
                case Some(userId) =>
                  val newSession = Events.fire(new LoginEvent(identity)).getOrElse(session)
                  Authenticator.create(identity).fold(
                    error => throw error,
                    authenticator =>
                      Ok(Json.obj("code" -> "user_logged_in", "sessionId" -> authenticator.id))
                        .withSession(newSession - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                        .withCookies(authenticator.toCookie)
                  )
              }
          }
        case Failure(t) =>
          log.error(s"[accessTokenLogin($provider)] Caught Exception($t) during fillProfile; token=${oauth2Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTraceString}")
          BadRequest(Json.obj("error" -> "invalid token"))
      }
    }
    resOpt getOrElse BadRequest(Json.obj("error" -> "invalid arguments"))
  }

  def accessTokenLogin(providerName: String) = Action(parse.tolerantJson) { implicit request =>
    (for {
      provider <- Registry.providers.get(providerName)
      oAuth2Info <- request.body.asOpt[OAuth2Info]
    } yield {
      Try {
        provider.fillProfile(SocialUser(IdentityId("", provider.id), "", "", "", None, None, provider.authMethod, oAuth2Info = Some(oAuth2Info)))
      } match {
        case Success(socialUser) =>
          authCommander.loginWithTrustedSocialIdentity(socialUser.identityId)
        case Failure(t) =>
          log.error(s"[accessTokenLogin($providerName)] Caught Exception($t) during fillProfile; token=${oAuth2Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTraceString}")
          BadRequest(Json.obj("error" -> "invalid_token"))
      }
    }) getOrElse {
      BadRequest(Json.obj("error" -> "invalid_arguments"))
    }
  }

  def socialFinalizeAccountAction() = JsonAction.parseJson(allowPending = true)(
    authenticatedAction = authHelper.doSocialFinalizeAccountAction(_),
    unauthenticatedAction = authHelper.doSocialFinalizeAccountAction(_)
  )

  def loginWithUserPass(link: String) = Action.async(parse.anyContent) { implicit request =>
    ProviderController.authenticate("userpass")(request).map {
      case res: SimpleResult if res.header.status == 303 =>
        authHelper.authHandler(request, res) { (cookies:Seq[Cookie], sess:Session) =>
          val newSession = if (link != "") {
            sess - SecureSocial.OriginalUrlKey + (AuthController.LinkWithKey -> link) // removal of OriginalUrlKey might be redundant
          } else sess
          Ok(Json.obj("code" -> "auth_success")).withCookies(cookies: _*).withSession(newSession)
        }
      case res => res
    }
  }

  def userPasswordSignup() = JsonAction.parseJson(allowPending = true)(
    authenticatedAction = authHelper.userPasswordSignupAction(_),
    unauthenticatedAction = authHelper.userPasswordSignupAction(_)
  )

  def userPassFinalizeAccountAction() = JsonAction.parseJsonAsync(allowPending = true)(
    authenticatedAction = authHelper.doUserPassFinalizeAccountAction(_),
    unauthenticatedAction = _ => resolve(Forbidden(JsNumber(0)))
  )

  def uploadBinaryPicture() = JsonAction(allowPending = true, parser = parse.temporaryFile)(
    authenticatedAction = authHelper.doUploadBinaryPicture(_),
    unauthenticatedAction = authHelper.doUploadBinaryPicture(_))

  def uploadFormEncodedPicture() = JsonAction(allowPending = true, parser = parse.multipartFormData)(
    authenticatedAction = authHelper.doUploadFormEncodedPicture(_),
    unauthenticatedAction = authHelper.doUploadFormEncodedPicture(_)
  )

  // this one sends an email with a link to a page -- more work for mobile likely needed
  def forgotPassword() = JsonAction.parseJson(allowPending = true)(
    authenticatedAction = authHelper.doForgotPassword(_),
    unauthenticatedAction = authHelper.doForgotPassword(_)
  )

  def linkSocialNetwork(providerName:String) = JsonAction.authenticatedParseJson(allowPending = true) { implicit request =>
    log.info(s"[linkSocialNetwork($providerName)] curr user: ${request.user} token: ${request.body}")
    val resOpt = for {
      provider   <- Registry.providers.get(providerName)
      oauth2Info <- request.body.asOpt[OAuth2Info]
    } yield {
      val suiOpt = db.readOnly(attempts = 2) { implicit s =>
        socialUserInfoRepo.getByUser(request.userId)
      } find (_.networkType == SocialNetworkType(providerName)) headOption

      val result = suiOpt match {
        case Some(sui) =>
          log.info(s"[accessTokenSignup($providerName)] user(${request.user}) already associated with social user: ${sui}")
          Ok(Json.obj("code" -> "link_already_exists")) // err on safe side
        case None =>
          Try  {
            val socialUser = SocialUser(IdentityId("", provider.id), "", "", "", None, None, provider.authMethod, oAuth2Info = Some(oauth2Info))
            val filledSocialUser = provider.fillProfile(socialUser)
            filledSocialUser
          } match {
            case Failure(err) => BadRequest(Json.obj("error" -> "invalid token"))
            case Success(filledUser) =>
              val saved = UserService.save(UserIdentity(Some(request.userId), filledUser)) // todo: check allowSignup
              log.info(s"[accessTokenSignup($providerName)] created social user: $saved")
              Ok(Json.obj("code" -> "link_created"))
          }
      }
      result
    }
    resOpt getOrElse BadRequest(Json.obj("error" -> "invalid_arguments"))
  }
}
