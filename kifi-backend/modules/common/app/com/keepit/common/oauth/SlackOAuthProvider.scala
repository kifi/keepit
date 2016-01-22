package com.keepit.common.oauth

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.slack.{ SlackAPI, SlackClient }
import com.keepit.slack.models._
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
    def getParameter(key: String) = request.queryString.get(key).flatMap(_.headOption)
    val REDIRECT_URI = BetterRoutesHelper.authenticate("slack").absoluteURL(true)
    getParameter("code") match {
      case None =>
        val fakeState = SlackState("fake") // todo(Léo): generate state
        val slackTeamId = getParameter("slackTeamId").map(SlackTeamId(_))
        Future.successful(Left(Results.Redirect(SlackAPI.OAuthAuthorize(SlackAuthScope.userSignup, fakeState, slackTeamId, REDIRECT_URI).url, SEE_OTHER)))
      case Some(code) => {
        getParameter("state") // todo(Léo: verify state
        slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), REDIRECT_URI).imap(Right(_))
      }
    }
  }
}
