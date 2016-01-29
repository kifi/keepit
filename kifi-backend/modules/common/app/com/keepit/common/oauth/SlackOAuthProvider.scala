package com.keepit.common.oauth

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.slack.{ SlackAuthStateCommander, SlackClient }
import com.keepit.slack.models._
import com.keepit.social.IdentityHelpers
import play.api.http.Status._
import play.api.mvc.{ Results, Result, Request }
import securesocial.core.IdentityId

import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.core._
import com.keepit.slack._

trait SlackOAuthProvider extends OAuthProvider[SlackAuthorizationResponse, SlackIdentity] {
  def providerId: ProviderId = ProviderIds.Slack
}

@Singleton
class SlackOAuthProviderImpl @Inject() (
    slackClient: SlackClient,
    slackStateCommander: SlackAuthStateCommander,
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
    getParameter("error") match {
      case Some(errorCode) => Future.successful(Left(Results.BadRequest(errorCode)))
      case None => getParameter("code") match {
        case None =>
          val slackTeamId = getParameter("slackTeamId").map(SlackTeamId(_))
          val action = Authenticate()
          val requiredScopes = action.getMissingScopes(Set.empty)
          val link = slackStateCommander.getAuthLink(action, slackTeamId, requiredScopes, REDIRECT_URI).url
          Future.successful(Left(Results.Redirect(link, SEE_OTHER).withSession(request.session)))
        case Some(code) => {
          getParameter("state").flatMap(state => slackStateCommander.getSlackAction(SlackAuthState(state))) match {
            case Some(Authenticate()) => slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), REDIRECT_URI).imap(Right(_))
            case _ => Future.successful(Left(SlackAPIFailure.InvalidAuthState.asResponse))
          }
        }
      }
    }
  }
}
