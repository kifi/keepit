package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.{ LibraryAccessActions, OrganizationAccessActions }
import com.keepit.slack._
import com.keepit.slack.models._

import scala.concurrent.{ ExecutionContext, Future }

object SlackOAuthController {
  val REDIRECT_URI = "https://www.kifi.com/oauth2/slack"
}

@Singleton
class SlackOAuthController @Inject() (
  slackClient: SlackClient,
  slackCommander: SlackCommander,
  slackStateCommander: SlackAuthStateCommander,
  slackIntegrationCommander: SlackIntegrationCommander,
  slackTeamCommander: SlackTeamCommander,
  authCommander: SlackAuthenticationCommander,
  pathCommander: PathCommander,
  slackToLibRepo: SlackChannelToLibraryRepo,
  libToSlackRepo: LibraryToSlackChannelRepo,
  userRepo: UserRepo,
  slackMembershipRepo: SlackTeamMembershipRepo,
  slackInfoCommander: SlackInfoCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  userValueRepo: UserValueRepo,
  val permissionCommander: PermissionCommander,
  val userActionsHelper: UserActionsHelper,
  val libraryAccessCommander: LibraryAccessCommander,
  val db: Database,
  airbrake: AirbrakeNotifier,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val ec: ExecutionContext)
    extends UserActions with OrganizationAccessActions with LibraryAccessActions with ShoeboxServiceController {

  def registerSlackAuthorization(codeOpt: Option[String], state: String) = UserAction.async { implicit request =>
    implicit val scopesFormat = SlackAuthScope.dbFormat
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val resultFut = for {
      code <- codeOpt.map(Future.successful).getOrElse(Future.failed(SlackAPIFailure.NoAuthCode))
      action <- slackStateCommander.getSlackAction(SlackAuthState(state)).map(Future.successful).getOrElse(Future.failed(SlackAPIFailure.InvalidAuthState))
      slackAuth <- slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), SlackOAuthController.REDIRECT_URI)
      slackIdentity <- slackClient.identifyUser(slackAuth.accessToken)
      result <- {
        slackCommander.registerAuthorization(request.userIdOpt, slackAuth, slackIdentity)
        authCommander.processAuthorizedAction(request.userId, slackIdentity.teamId, slackIdentity.userId, action, slackAuth.incomingWebhook)
      }
    } yield {
      result match {
        case SlackResponse.RedirectClient(url) => Redirect(url, SEE_OTHER)
        case SlackResponse.ActionPerformed(url) => Redirect(url getOrElse "/", SEE_OTHER)
      }
    }

    resultFut.recover {
      case fail =>
        airbrake.notify(s"Slack authorization failed for user ${request.userId} with state $state and code $codeOpt", fail)
        Redirect("/", SEE_OTHER) // we could have an explicit error page here
    }
  }

  def addSlackTeam(slackTeamId: Option[SlackTeamId]) = UserAction.async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val res = authCommander.processActionOrElseAuthenticate(request.userId, slackTeamId, AddSlackTeam())
    handleAsBrowserRequest(res)
  }

  def createSlackTeam(slackTeamId: Option[SlackTeamId]) = UserAction.async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val res = authCommander.processActionOrElseAuthenticate(request.userId, slackTeamId, CreateSlackTeam())
    handleAsBrowserRequest(res)(request)
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
        Redirect(fail.bestEffortRedirect.fold("/")(_.absolute))
      case badFail =>
        airbrake.notify("Uncategorized failure for SlackAction", badFail)
        Redirect("/")
    }
  }
}

