package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.AuthCommander
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, MobileController}
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.controllers.core.{AuthController, AuthHelper}
import com.keepit.model.{SocialUserInfoRepo, UserRepo}
import com.keepit.serializer.SocialUserSerializer.oAuth2InfoSerializer
import com.keepit.social.{SocialNetworkType, SocialId, UserIdentity}
import com.keepit.social.providers.ProviderController

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsValue, JsNumber, Json}
import play.api.mvc.{Action, Cookie, Session, SimpleResult}

import scala.Some
import scala.util.{Failure, Success, Try}

import securesocial.core.{Authenticator, Events, IdentityId, IdentityProvider, LoginEvent}
import securesocial.core.{OAuth1Provider, OAuth2Info, Registry, SecureSocial, SocialUser, UserService}

class MobileAuthController @Inject() (
  airbrakeNotifier:AirbrakeNotifier,
  actionAuthenticator:ActionAuthenticator,
  db: Database,
  clock: Clock,
  authCommander: AuthCommander,
  socialUserInfoRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  authHelper:AuthHelper
) extends MobileController(actionAuthenticator) with ShoeboxServiceController with Logging {

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
