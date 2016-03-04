package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.slack._
import com.keepit.slack.models._

import scala.concurrent.{ ExecutionContext, Future }

object SlackOAuthController {
  val REDIRECT_URI = "https://www.kifi.com/oauth2/slack"
}

@Singleton
class SlackOAuthController @Inject() (
  slackClient: SlackClient,
  slackIdentityCommander: SlackIdentityCommander,
  slackAuthCommander: SlackAuthenticationCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  val userActionsHelper: UserActionsHelper,
  airbrake: AirbrakeNotifier,
  implicit val ec: ExecutionContext)
    extends UserActions with ShoeboxServiceController {

  def registerSlackAuthorization(codeOpt: Option[String], state: String) = UserAction.async { implicit request =>
    implicit val scopesFormat = SlackAuthScope.dbFormat
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val resultFut = for {
      code <- codeOpt.map(Future.successful).getOrElse(Future.failed(SlackFail.NoAuthCode))
      action <- slackAuthCommander.getSlackAction(SlackAuthState(state)).map(Future.successful).getOrElse(Future.failed(SlackFail.InvalidAuthState))
      slackAuth <- slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), SlackOAuthController.REDIRECT_URI)
      slackIdentity <- slackClient.identifyUser(slackAuth.accessToken)
      result <- {
        val webhookIdOpt = slackIdentityCommander.registerAuthorization(request.userIdOpt, slackAuth, slackIdentity)
        slackAuthCommander.processAuthorizedAction(request.userId, slackIdentity.teamId, slackIdentity.userId, action, webhookIdOpt)
      }
    } yield {
      result match {
        case SlackResponse.RedirectClient(url) => Redirect(url, SEE_OTHER)
        case SlackResponse.ActionPerformed(url) => Redirect(url getOrElse "/", SEE_OTHER)
      }
    }

    resultFut.recover {
      case fail: SlackActionFail =>
        log.error(s"[SlackActionFail] Slack authorization failed for user ${request.userId} with state $state, code $codeOpt, error code ${fail.code}, and message ${fail.msg}")
        airbrake.notify(s"Slack authorization failed for user ${request.userId} with state $state and code $codeOpt", fail)
        Redirect(s"/?error=${fail.code}", SEE_OTHER) // we could have an explicit error page here
      case fail =>
        log.error(s"[SlackActionFail] Slack authorization failed for user ${request.userId} with state $state, code $codeOpt")
        airbrake.notify(s"Slack authorization failed for user ${request.userId} with state $state and code $codeOpt", fail)
        Redirect(s"/?error=unknown", SEE_OTHER)
    }
  }

  def addSlackTeam(slackTeamId: Option[SlackTeamId]) = UserAction.async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val res = slackAuthCommander.processActionOrElseAuthenticate(request.userId, slackTeamId, AddSlackTeam(andThen = None))
    handleAsBrowserRequest(res)
  }

  // Can elegantly handle redirects (to HTML pages), *never* speaks JSON, should not be on /site/ routes
  private def handleAsBrowserRequest[T](response: Future[SlackResponse])(implicit request: UserRequest[T]) = {
    response.map {
      case SlackResponse.RedirectClient(url) => Redirect(url, SEE_OTHER)
      case SlackResponse.ActionPerformed(urlOpt) =>
        val url = urlOpt.orElse(request.headers.get(REFERER).filter(_.startsWith("https://www.kifi.com"))).getOrElse("/")
        Redirect(url)
    }.recover {
      case fail: SlackActionFail =>
        log.warn(s"[SlackOAuthController#handleAsBrowserRequest] Error: ${fail.code}")
        Redirect(fail.bestEffortRedirect.fold(s"/?error=${fail.code}")(_.absolute))
      case badFail =>
        airbrake.notify("Uncategorized failure for SlackAction", badFail)
        Redirect("/?error=unknown")
    }
  }
}

