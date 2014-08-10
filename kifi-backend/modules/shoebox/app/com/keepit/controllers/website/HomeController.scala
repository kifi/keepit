package com.keepit.controllers.website

import com.google.inject.Inject

import com.keepit.commanders.{ InviteCommander, UserCommander, LocalUserExperimentCommander }
import com.keepit.common.KestrelCombinator
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, AuthenticatedRequest, WebsiteController }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.net.UserAgent
import com.keepit.common.service.FortyTwoServices
import com.keepit.controllers.core.AuthController
import com.keepit.curator.CuratorServiceClient
import com.keepit.heimdal._
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.social.{ SocialNetworks, SocialNetworkType, SocialGraphPlugin }

import ActionAuthenticator.MaybeAuthenticatedRequest
import play.api
import play.api.libs.json.{ JsValue, JsObject, JsString, Json }
import play.api.Play

import play.api.Play.current
import play.api.http.HeaderNames.USER_AGENT
import play.api.libs.iteratee.Enumerator
import play.api.mvc._
import play.api.mvc.DiscardingCookie
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import securesocial.core.{ SecureSocial, Authenticator }

import scala.Some
import scala.concurrent.Future

class HomeController @Inject() (
  db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: UserEmailAddressRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator,
  emailAddressRepo: UserEmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialGraphPlugin: SocialGraphPlugin,
  fortyTwoServices: FortyTwoServices,
  userCache: SocialUserInfoUserCache,
  userCommander: UserCommander,
  inviteCommander: InviteCommander,
  heimdalServiceClient: HeimdalServiceClient,
  userExperimentCommander: LocalUserExperimentCommander,
  curatorServiceClient: CuratorServiceClient,
  applicationConfig: FortyTwoConfig)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with Logging {

  private def hasSeenInstall(implicit request: AuthenticatedRequest[_]): Boolean = {
    // Sign-up flow is critical, read from master
    db.readOnlyMaster { implicit s => userValueRepo.getValue(request.userId, UserValues.hasSeenInstall) }
  }

  private def setHasSeenInstall()(implicit request: AuthenticatedRequest[_]): Unit = {
    db.readWrite(attempts = 3) { implicit s => userValueRepo.setValue(request.userId, UserValues.hasSeenInstall.name, true) }
  }

  def version = Action {
    Ok(fortyTwoServices.currentVersion.toString)
  }

  def about = HtmlAction(authenticatedAction = aboutHandler(isLoggedIn = true)(_), unauthenticatedAction = aboutHandler(isLoggedIn = false)(_))
  private def aboutHandler(isLoggedIn: Boolean)(implicit request: Request[_]): Result = {
    request.request.headers.get(USER_AGENT).map { agentString =>
      val agent = UserAgent.fromString(agentString)
      if (agent.isOldIE) {
        Some(Redirect(com.keepit.controllers.website.routes.HomeController.unsupported()))
      } else if (!agent.screenCanFitWebApp) {
        Some(Redirect(com.keepit.controllers.website.routes.HomeController.mobileLanding()))
      } else None
    }.flatten.getOrElse(Ok(views.html.marketing.about(isLoggedIn)))
  }

  def termsOfService = HtmlAction(authenticatedAction = termsHandler(isLoggedIn = true)(_), unauthenticatedAction = termsHandler(isLoggedIn = false)(_))
  private def termsHandler(isLoggedIn: Boolean)(implicit request: Request[_]): Result = {
    request.request.headers.get(USER_AGENT).map { agentString =>
      val agent = UserAgent.fromString(agentString)
      if (agent.isOldIE) {
        None
      } else if (!agent.screenCanFitWebApp) {
        Some(true)
      } else {
        Some(false)
      }
    }.getOrElse(Some(false)).map { hideHeader =>
      Ok(views.html.marketing.terms(isLoggedIn, hideHeader))
    }.getOrElse(Redirect(com.keepit.controllers.website.routes.HomeController.unsupported()))
  }

  def privacyPolicy = HtmlAction(authenticatedAction = privacyHandler(isLoggedIn = true)(_), unauthenticatedAction = privacyHandler(isLoggedIn = false)(_))
  private def privacyHandler(isLoggedIn: Boolean)(implicit request: Request[_]): Result = {
    request.request.headers.get(USER_AGENT).map { agentString =>
      val agent = UserAgent.fromString(agentString)
      if (agent.isOldIE) {
        None
      } else if (!agent.screenCanFitWebApp) {
        Some(true)
      } else {
        Some(false)
      }
    }.getOrElse(Some(false)).map { hideHeader =>
      Ok(views.html.marketing.privacy(isLoggedIn, hideHeader))
    }.getOrElse(Redirect(com.keepit.controllers.website.routes.HomeController.unsupported()))
  }

  def iPhoneAppStoreRedirect = HtmlAction(authenticatedAction = iPhoneAppStoreRedirectWithTracking(_), unauthenticatedAction = iPhoneAppStoreRedirectWithTracking(_))
  private def iPhoneAppStoreRedirectWithTracking(implicit request: RequestHeader): Result = {
    val context = new HeimdalContextBuilder()
    context.addRequestInfo(request)
    context += ("type", "landing")
    heimdalServiceClient.trackEvent(AnonymousEvent(context.build, EventType("visitor_viewed_page")))
    Redirect("https://itunes.apple.com/app/id740232575")
  }

  def mobileLanding = HtmlAction(authenticatedAction = mobileLandingHandler(_), unauthenticatedAction = mobileLandingHandler(_))
  private def mobileLandingHandler(implicit request: Request[_]): Result = {
    if (request.headers.get("User-Agent").exists { ua => ua.contains("iPhone") && !ua.contains("iPad") }) {
      iPhoneAppStoreRedirectWithTracking
    } else {
      Ok(views.html.marketing.mobileLanding(""))
    }
  }

  def home = {
    val htmlAction = HtmlAction(authenticatedAction = homeAuthed(_), unauthenticatedAction = homeNotAuthed(_))
    Action.async(htmlAction.parser) { request =>
      if (request.host.contains("42go")) {
        Future.successful(MovedPermanently(applicationConfig.applicationBaseUrl + "/about/mission.html"))
      } else {
        htmlAction(request)
      }
    }
  }

  private def homeAuthed(implicit request: AuthenticatedRequest[_]): Result = {
    val linkWith = request.session.get(AuthController.LinkWithKey)
    val agentOpt = request.headers.get("User-Agent").map { agent =>
      UserAgent.fromString(agent)
    }
    if (linkWith.isDefined) {
      Redirect(com.keepit.controllers.core.routes.AuthController.link(linkWith.get))
        .withSession(session - AuthController.LinkWithKey)
    } else if (request.user.state == UserStates.PENDING) {
      pendingHome()
    } else if (request.user.state == UserStates.INCOMPLETE_SIGNUP) {
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else if (request.kifiInstallationId.isEmpty && !hasSeenInstall) {
      Redirect(routes.HomeController.install())
    } else if (agentOpt.exists(_.screenCanFitWebApp)) {
      AngularDistAssets.angularApp()
    } else {
      Redirect(routes.HomeController.unsupported())
    }
  }

  def unsupported = Action {
    Status(200).chunked(Enumerator.fromStream(Play.resourceAsStream("public/unsupported.html").get)) as HTML
  }

  private def homeNotAuthed(implicit request: Request[_]): Result = {
    if (request.identityOpt.isDefined) {
      // User needs to sign up or (social) finalize
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else {
      // TODO: Redirect to /login if the path is not /
      // Non-user landing page
      temporaryReportLandingLoad()
      val agentOpt = request.headers.get("User-Agent").map(UserAgent.fromString)
      if (agentOpt.exists(!_.screenCanFitWebApp)) {
        val ua = agentOpt.get.userAgent
        val isIphone = ua.contains("iPhone") && !ua.contains("iPad")
        if (isIphone) {
          iPhoneAppStoreRedirectWithTracking
        } else {
          Ok(views.html.marketing.mobileLanding(""))
        }
      } else {
        Ok(views.html.marketing.landing())
      }
    }
  }

  def kifeeeed = HtmlAction.authenticated { request =>
    if (userExperimentCommander.userHasExperiment(request.userId, ExperimentType.ADMIN)) {
      homeAuthed(request)
    } else {
      Redirect("/")
    }
  }

  // this is for testing and will eventually be thrown away
  def kifeeeedEmail(code: String) = HtmlAction.authenticatedAsync { request =>
    if (userExperimentCommander.userHasExperiment(request.userId, ExperimentType.DIGEST_EMAIl)) {
      log.info(s"kifeeeedEmail($code)requested by " + request.userId)
      if (code.endsWith("-all"))
        curatorServiceClient.triggerEmail(code.substring(0, code.length - 4)).map { res => Ok(JsString(res)) }
      else
        curatorServiceClient.triggerEmailToUser(code, request.userId).map { res => Ok(JsString(res)) }
    } else {
      log.info(s"kifeeeedEmail($code) rejected for " + request.userId)
      Future.successful(Redirect("/"))
    }
  }

  private def temporaryReportLandingLoad()(implicit request: RequestHeader): Unit = SafeFuture {
    val context = new HeimdalContextBuilder()
    context.addRequestInfo(request)
    heimdalServiceClient.trackEvent(AnonymousEvent(context.build, EventType("loaded_landing_page")))
  }

  def agent = Action { request =>
    val res = request.headers.get("User-Agent").map { ua =>
      val parsed = UserAgent.fromString(ua)
      (parsed.name, parsed.operatingSystemFamily, parsed.operatingSystemName, parsed.typeName, parsed.userAgent, parsed.version)
    }
    Ok(res.toString)
  }

  def homeWithParam(id: String) = home

  def blog = HtmlAction[AnyContent](allowPending = true)(authenticatedAction = { request =>
    request.headers.get(USER_AGENT) match {
      case Some(ua) if ua.contains("Mobi") => Redirect("http://kifiupdates.tumblr.com")
      case _ => homeAuthed(request)
    }
  }, unauthenticatedAction = { request =>
    request.headers.get(USER_AGENT) match {
      case Some(ua) if ua.contains("Mobi") => Redirect("http://kifiupdates.tumblr.com")
      case _ => homeNotAuthed(request)
    }
  })

  def kifiSiteRedirect(path: String) = Action {
    MovedPermanently(s"/$path")
  }

  def pendingHome()(implicit request: AuthenticatedRequest[_]) = {
    val user = request.user

    val (email, friendsOnKifi) = db.readOnlyMaster { implicit session =>
      val email = emailRepo.getAllByUser(user.id.get).sortBy(a => a.id.get.id).lastOption.map(_.address)
      val friendsOnKifi = userConnectionRepo.getConnectedUsers(user.id.get).map { u =>
        val user = userRepo.get(u)
        if (user.state == UserStates.ACTIVE) Some(user.externalId)
        else None
      }.flatten

      (email, friendsOnKifi)
    }
    Ok(views.html.website.onboarding.userRequestReceived2(
      user = user,
      email = email.map(_.address),
      justVerified = request.queryString.get("m").exists(_.headOption == Some("1")),
      friendsOnKifi = friendsOnKifi)).discardingCookies(DiscardingCookie("inv"))
  }

  def install = HtmlAction.authenticated { implicit request =>
    SafeFuture {
      userCommander.tellUsersWithContactOfNewUserImmediate(request.user)

      // Temporary event for debugging purpose
      val context = new HeimdalContextBuilder()
      context.addRequestInfo(request)
      heimdalServiceClient.trackEvent(UserEvent(request.user.id.get, context.build, EventType("loaded_install_page")))
    }
    setHasSeenInstall()
    request.request.headers.get(USER_AGENT).map { agentString =>
      val agent = UserAgent.fromString(agentString)
      log.info(s"trying to log in via $agent. orig string: $agentString")
      if (!agent.screenCanFitWebApp) {
        Some(Redirect(com.keepit.controllers.website.routes.HomeController.mobileLanding()))
      } else if (!agent.canRunExtensionIfUpToDate) {
        Some(Redirect(com.keepit.controllers.website.routes.HomeController.unsupported()))
      } else None
    }.flatten.getOrElse(Ok(views.html.website.install(request.user)))
  }

  // todo: move this to UserController
  def disconnect(networkString: String) = HtmlAction.authenticated { implicit request =>
    val (suiOpt, code) = userCommander.disconnect(request.userId, networkString)
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
              .withSession(session - SecureSocial.OriginalUrlKey + (ActionAuthenticator.FORTYTWO_USER_ID -> newLoginUser.userId.get.toString)) // note: newLoginuser.userId
              .withCookies(authenticator.toCookie)
          }
        )
    }
  }

  // Do not remove until at least 1 Mar 2014. The extension sends users to this URL after installation.
  def gettingStarted = Action { request =>
    MovedPermanently("/")
  }

}
