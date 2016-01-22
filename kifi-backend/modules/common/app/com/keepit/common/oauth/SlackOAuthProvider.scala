package com.keepit.common.oauth

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.slack.{ SlackAPI, SlackClient }
import com.keepit.slack.models.{ SlackAuthorizationCode, SlackState, SlackAuthScope, SlackAuthorizationResponse }
import com.keepit.social.IdentityHelpers
import play.api.http.Status._
import play.api.mvc.{ Results, Result, Request }
import securesocial.core.IdentityId

import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.core._

trait SlackOAuthProvider extends OAuthProvider[SlackAuthorizationResponse, SlackIdentity] {
  def providerId: ProviderId = ProviderIds.Slack
}

@Singleton
class SlackOAuthProviderImpl @Inject() (
    slackClient: SlackClient,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends SlackOAuthProvider with Logging {

  def getIdentityId(auth: SlackAuthorizationResponse): Future[IdentityId] = {
    slackClient.identifyUser(auth.accessToken).imap(response => IdentityHelpers.toIdentityId(response.teamId, response.userId))
  }

  def getRichIdentity(auth: SlackAuthorizationResponse): Future[SlackIdentity] = {
    for {
      userIdentity <- slackClient.identifyUser(auth.accessToken)
      userInfo <- slackClient.getUserInfo(auth.accessToken, userIdentity.userId)
    } yield SlackIdentity(auth, userIdentity, Some(userInfo))
  }

  def doOAuth[A]()(implicit request: Request[A]): Future[Either[Result, SlackAuthorizationResponse]] = {
    val REDIRECT_URI = BetterRoutesHelper.authenticate("slack").absoluteURL(true)
    // todo(Léo): handle state
    val fakeState = SlackState("fake")
    request.queryString.get(OAuth2Constants.Code).flatMap(_.headOption) match {
      case None => Future.successful(Left(Results.Redirect(SlackAPI.OAuthAuthorize(SlackAuthScope.userSignup, fakeState, None, REDIRECT_URI).url, SEE_OTHER)))
      case Some(code) => slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), REDIRECT_URI).imap(Right(_))
    }
  }
}
