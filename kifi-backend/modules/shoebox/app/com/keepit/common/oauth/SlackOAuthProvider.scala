package com.keepit.common.oauth

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.slack.SlackClient
import com.keepit.slack.models._
import com.keepit.social.IdentityHelpers
import play.api.http.Status._
import play.api.libs.json.Json
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
    slackClient: SlackClientWrapper,
    slackAuthCommander: SlackAuthenticationCommander,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends SlackOAuthProvider with Logging {

  def getIdentityId(auth: SlackAuthorizationResponse): Future[IdentityId] = {
    auth match {
      case appAuth: SlackAppAuthorizationResponse => slackClient.identifyUser(auth.accessToken).imap(identity => (identity.teamId, identity.userId))
      case identityAuth: SlackIdentityAuthorizationResponse => Future.successful((identityAuth.teamId, identityAuth.userId))
    }
  }.imap { case (teamId, userId) => IdentityHelpers.toIdentityId(teamId, userId) }

  def getRichIdentity(auth: SlackAuthorizationResponse): Future[SlackIdentity] = {
    for {
      (teamId, userId, toSlackIdentity) <- auth match {
        case appAuth: SlackAppAuthorizationResponse => slackClient.identifyUser(auth.accessToken).imap(identity => (identity.teamId, identity.userId, SlackIdentity(appAuth, identity, _: Option[FullSlackUserInfo])))
        case identityAuth: SlackIdentityAuthorizationResponse => slackClient.getUserIdentity(auth.accessToken).imap(identity => (identity.teamId, identity.userId, SlackIdentity(identityAuth, identity, _: Option[FullSlackUserInfo])))
      }
      fullUser <- {
        val preferredTokens = if (auth.scopes.contains(SlackAuthScope.UsersRead)) Seq(auth.accessToken) else Seq.empty
        slackClient.getUserInfo(teamId, userId, preferredTokens).imap(Some(_)).recover { case SlackFail.NoValidToken => None }
      }
    } yield toSlackIdentity(fullUser)
  }

  def doOAuth[A]()(implicit request: Request[A]): Future[Either[Result, SlackAuthorizationResponse]] = {
    val knownErrorCodes = Set("access_denied")
    def getParameter(key: String) = request.queryString.get(key).flatMap(_.headOption)
    val REDIRECT_URI = BetterRoutesHelper.authenticate("slack").absoluteURL(true)
    getParameter("error") match {
      case Some(errorCode) =>
        if (!knownErrorCodes.contains(errorCode)) airbrake.notify(s"[SlackAuthError] unknown error code $errorCode from slack, query string = ${request.rawQueryString}")
        Future.successful(Left(Results.BadRequest(Json.obj("error" -> errorCode))))
      case None => getParameter("code") match {
        case None =>
          val slackTeamId = getParameter("slackTeamId").map(SlackTeamId(_))
          val useIdentityScopes = slackTeamId.exists(SlackAuthScope.Identity.areAvailableForTeam)
          val action = if (request.uri.contains("login")) Login(useIdentityScopes) else Signup(useIdentityScopes)
          slackAuthCommander.getIdentityAndMissingScopes(None, slackTeamId, action).imap {
            case (_, missingScopes) =>
              val link = slackAuthCommander.getAuthLink(action, slackTeamId, missingScopes, REDIRECT_URI).url
              Left(Results.Redirect(link, SEE_OTHER).withSession(request.session))
          }
        case Some(code) => {
          getParameter("state").flatMap(state => slackAuthCommander.getSlackAction(SlackAuthState(state))) match {
            case Some(Login(_) | Signup(_)) => slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), REDIRECT_URI).imap(Right(_))
            case _ =>
              airbrake.notify(s"[SlackAuthError] invalid query param state, query string = ${request.rawQueryString}")
              Future.successful(Left(SlackFail.InvalidAuthState.asResponse))
          }
        }
      }
    }
  }
}
