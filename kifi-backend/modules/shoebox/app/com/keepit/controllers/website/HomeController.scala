package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ InviteCommander, KeepsCommander, LocalUserExperimentCommander, UserCommander, UserConnectionsCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller._
import com.keepit.common.core._
import com.keepit.common.db.slick._
import com.keepit.common.http._
import com.keepit.common.logging.Logging
import com.keepit.common.net.UserAgent
import com.keepit.common.service.FortyTwoServices
import com.keepit.curator.CuratorServiceClient
import com.keepit.heimdal._
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.social.SocialGraphPlugin
import play.api.Play
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.mvc._
import play.twirl.api.Html
import securesocial.core.{ Authenticator, SecureSocial }

import KifiSession._

class HomeController @Inject() (
  db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: UserEmailAddressRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  val userActionsHelper: UserActionsHelper,
  emailAddressRepo: UserEmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialGraphPlugin: SocialGraphPlugin,
  fortyTwoServices: FortyTwoServices,
  userCache: SocialUserInfoUserCache,
  userCommander: UserCommander,
  userConnectionsCommander: UserConnectionsCommander,
  inviteCommander: InviteCommander,
  heimdalServiceClient: HeimdalServiceClient,
  userExperimentCommander: LocalUserExperimentCommander,
  curatorServiceClient: CuratorServiceClient,
  applicationConfig: FortyTwoConfig,
  keepsCommander: KeepsCommander,
  siteRouter: KifiSiteRouter)
    extends UserActions with ShoeboxServiceController with Logging {

  private def hasSeenInstall(implicit request: UserRequest[_]): Boolean = {
    // Sign-up flow is critical, read from master
    db.readOnlyMaster { implicit s => userValueRepo.getValue(request.userId, UserValues.hasSeenInstall) }
  }

  private def setHasSeenInstall()(implicit request: UserRequest[_]): Unit = {
    db.readWrite(attempts = 3) { implicit s => userValueRepo.setValue(request.userId, UserValues.hasSeenInstall.name, true) }
  }

  def home = MaybeUserAction { implicit request =>
    request match {
      case r: NonUserRequest[_] => MarketingSiteRouter.marketingSite()
      case userRequest: UserRequest[_] => AngularDistAssets.angularApp()
    }
  }

  def version = Action {
    Ok(fortyTwoServices.currentVersion.toString)
  }

  def robots = Action {
    Ok(
      """
        |User-agent: *
        |Disallow: /admin/
        |
        |SITEMAP: https://www.kifi.com/sitemap/libraries-0.xml
      """.stripMargin)
  }

  def getKeepsCount = Action.async {
    keepsCommander.getKeepsCountFuture() imap { count =>
      Ok(count.toString)
    }
  }

  def route(path: String) = Action { implicit request =>
    MarketingSiteRouter.marketingSite(path)
  }

  def moved(uri: String) = Action {
    MovedPermanently(uri)
  }

  def iPhoneAppStoreRedirect = MaybeUserAction { implicit request =>
    iPhoneAppStoreRedirectWithTracking
  }
  def iPhoneAppStoreRedirectWithTracking(implicit request: RequestHeader): Result = {
    SafeFuture {
      val context = new HeimdalContextBuilder()
      context.addRequestInfo(request)
      context += ("type", "landing")
      heimdalServiceClient.trackEvent(AnonymousEvent(context.build, EventType("visitor_viewed_page")))
    }
    Ok(views.html.mobile.iPhoneRedirect(request.uri))
  }

  def unsupported = Action {
    Status(200).chunked(Enumerator.fromStream(Play.resourceAsStream("public/unsupported.html").get)) as HTML
  }

  private def temporaryReportLandingLoad()(implicit request: RequestHeader): Unit = SafeFuture {
    val context = new HeimdalContextBuilder()
    context.addRequestInfo(request)
    heimdalServiceClient.trackEvent(AnonymousEvent(context.build, EventType("loaded_landing_page")))
  }

  def agent = Action { request =>
    val res = request.headers.get("User-Agent").map { ua =>
      val parsed = UserAgent(ua)
      (parsed.name, parsed.operatingSystemFamily, parsed.operatingSystemName, parsed.typeName, parsed.userAgent, parsed.version)
    }
    Ok(res.toString)
  }

  def install = UserPage { implicit request =>
    SafeFuture {
      if (!hasSeenInstall) userCommander.tellUsersWithContactOfNewUserImmediate(request.user)

      // Temporary event for debugging purpose
      val context = new HeimdalContextBuilder()
      context.addRequestInfo(request)
      heimdalServiceClient.trackEvent(UserEvent(request.user.id.get, context.build, EventType("loaded_install_page")))
    }
    setHasSeenInstall()
    request.headers.get(USER_AGENT).map { agentString =>
      val agent = UserAgent(agentString)
      log.info(s"trying to log in via $agent. orig string: $agentString")

      if (!agent.canRunExtensionIfUpToDate) {
        Some(AngularDistAssets.angularApp())
      } else None
    }.flatten.getOrElse(Ok(views.html.website.install(request.user)))
  }

  // todo: move this to UserController
  def disconnect(networkString: String) = UserAction { implicit request =>
    val (suiOpt, code) = userConnectionsCommander.disconnect(request.userId, networkString)
    suiOpt match {
      case None => code match {
        case "no_other_connected_network" => BadRequest("You must have at least one other network connected.")
        case "not_connected_to_network" => BadRequest(s"You are not connected to ${networkString}.")
        case _ => Status(INTERNAL_SERVER_ERROR)("0")
      }
      case Some(newLoginUser) =>
        val identity = newLoginUser.credentials.get
        Authenticator.create(identity).fold(
          error => Status(INTERNAL_SERVER_ERROR)("0"),
          authenticator => {
            Redirect("/profile") // hard coded because reverse router doesn't let us go there. todo: fix
              .withSession((request.session - SecureSocial.OriginalUrlKey).setUserId(newLoginUser.userId.get))
              .withCookies(authenticator.toCookie)
          }
        )
    }
  }

  // Do not remove until at least 1 Mar 2014. The extension sends users to this URL after installation.
  // It's okay, comment from 21 Jan 2014. This method is safe here.
  def gettingStarted = Action { request =>
    MovedPermanently("/")
  }

  def getKifiExtensionIPhone(s: String) = Action { implicit request =>
    Ok(Html("""<img src="http://djty7jcqog9qu.cloudfront.net/assets/site/keep-from-other-apps.png" style="width: 100%;">"""))
  }

}
