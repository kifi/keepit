package com.keepit.controllers.website

import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, AuthenticatedRequest, WebsiteController}
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.controllers.core.AuthController
import com.keepit.model._
import com.keepit.social.{SocialNetworks, SocialNetworkType, SocialGraphPlugin}
import com.keepit.common.akka.SafeFuture
import com.keepit.commanders.{InviteCommander, UserCommander}
import com.keepit.common.db.ExternalId
import com.keepit.common.KestrelCombinator

import ActionAuthenticator.MaybeAuthenticatedRequest

import play.api.Play.current
import play.api._
import play.api.http.HeaderNames.USER_AGENT
import play.api.libs.iteratee.Enumerator
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.commanders.{UserCommander, InviteCommander}
import com.keepit.common.db.ExternalId

import securesocial.core.{SecureSocial, Authenticator}

import com.google.inject.Inject
import com.keepit.common.net.UserAgent
import com.keepit.heimdal._
import scala.Some
import play.api.mvc.DiscardingCookie
import com.keepit.common.controller.AuthenticatedRequest

class HomeController @Inject() (
  db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator,
  emailAddressRepo: EmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialGraphPlugin: SocialGraphPlugin,
  fortyTwoServices: FortyTwoServices,
  userCache: SocialUserInfoUserCache,
  userCommander: UserCommander,
  inviteCommander: InviteCommander,
  heimdalServiceClient: HeimdalServiceClient)
  extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with Logging {

  private def hasSeenInstall(implicit request: AuthenticatedRequest[_]): Boolean = {
    db.readOnly { implicit s => userValueRepo.getValue(request.userId, "has_seen_install").exists(_.toBoolean) }
  }

  private def setHasSeenInstall()(implicit request: AuthenticatedRequest[_]): Unit = {
    db.readWrite { implicit s => userValueRepo.setValue(request.userId, "has_seen_install", true.toString) }
  }

  def version = Action {
    Ok(fortyTwoServices.currentVersion.toString)
  }

  // Start post-launch stuff!
  def about = HtmlAction(authenticatedAction = aboutHandler(isLoggedIn = true)(_), unauthenticatedAction = aboutHandler(isLoggedIn = false)(_))
  private def aboutHandler(isLoggedIn: Boolean)(implicit request: Request[_]): SimpleResult = {
    Ok(views.html.marketing.about(isLoggedIn))
  }

  def termsOfService = HtmlAction(authenticatedAction = termsHandler(isLoggedIn = true)(_), unauthenticatedAction = termsHandler(isLoggedIn = false)(_))
  private def termsHandler(isLoggedIn: Boolean)(implicit request: Request[_]): SimpleResult = {
    Ok(views.html.marketing.terms(isLoggedIn))
  }

  def privacyPolicy = HtmlAction(authenticatedAction = privacyHandler(isLoggedIn = true)(_), unauthenticatedAction = privacyHandler(isLoggedIn = false)(_))
  private def privacyHandler(isLoggedIn: Boolean)(implicit request: Request[_]): SimpleResult = {
    Ok(views.html.marketing.privacy(isLoggedIn))
  }

  def mobileLanding = HtmlAction(authenticatedAction = mobileLandingHandler(isLoggedIn = true)(_), unauthenticatedAction = mobileLandingHandler(isLoggedIn = false)(_))
  private def mobileLandingHandler(isLoggedIn: Boolean)(implicit request: Request[_]): SimpleResult = {
    val agentOpt = request.headers.get("User-Agent").map { agent =>
      UserAgent.fromString(agent)
    }
    val ua = agentOpt.get.userAgent
    val isIphone = ua.contains("iPhone") && !ua.contains("iPad")
    val agentClass = if (isIphone) "iphone" else ""
    Ok(views.html.marketing.mobileLanding(false, agentClass))
  }
  // End post-launch stuff!

  def home = HtmlAction(authenticatedAction = homeAuthed(_), unauthenticatedAction = homeNotAuthed(_))

  private def homeAuthed(implicit request: AuthenticatedRequest[_]): SimpleResult = {
    val linkWith = request.session.get(AuthController.LinkWithKey)
    if (linkWith.isDefined) {
      Redirect(com.keepit.controllers.core.routes.AuthController.link(linkWith.get))
        .withSession(session - AuthController.LinkWithKey)
    } else if (request.user.state == UserStates.PENDING) {
      pendingHome()
    } else if (request.user.state == UserStates.INCOMPLETE_SIGNUP) {
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else if (request.kifiInstallationId.isEmpty && !hasSeenInstall) {
      Redirect(routes.HomeController.install())
    } else {
      Ok.stream(Enumerator.fromStream(Play.resourceAsStream("public/index.html").get)) as HTML
    }
  }

  def unsupported = Action {
    Ok.stream(Enumerator.fromStream(Play.resourceAsStream("public/unsupported.html").get)) as HTML
  }

  private def homeNotAuthed(implicit request: Request[_]): SimpleResult = {
    if (request.identityOpt.isDefined) {
      // User needs to sign up or (social) finalize
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else {
      // TODO: Redirect to /login if the path is not /
      // Non-user landing page
      val agentOpt = request.headers.get("User-Agent").map { agent =>
        UserAgent.fromString(agent)
      }
      temporaryReportLandingLoad()
      if (agentOpt.exists(_.isMobile)) {
        val ua = agentOpt.get.userAgent
        val isIphone = ua.contains("iPhone") && !ua.contains("iPad")
        val agentClass = if (isIphone) "iphone" else ""
        Ok(views.html.marketing.mobileLanding(false, agentClass))
      } else {
        Ok(views.html.marketing.landing())
      }
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

    inviteCommander.markPendingInvitesAsAccepted(user.id.get, request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))

    val (email, friendsOnKifi) = db.readOnly { implicit session =>
      val email = emailRepo.getAllByUser(user.id.get).sortBy(a => a.id.get.id).lastOption.map(_.address)
      val friendsOnKifi = userConnectionRepo.getConnectedUsers(user.id.get).map { u =>
        val user = userRepo.get(u)
        if(user.state == UserStates.ACTIVE) Some(user.externalId)
        else None
      }.flatten

      (email, friendsOnKifi)
    }
    Ok(views.html.website.onboarding.userRequestReceived2(
      user = user,
      email = email,
      justVerified = request.queryString.get("m").exists(_.headOption == Some("1")),
      friendsOnKifi = friendsOnKifi)).discardingCookies(DiscardingCookie("inv"))
  }

  def install = HtmlAction.authenticated { implicit request =>
    val toBeNotified = db.readWrite { implicit session =>
      for {
        su <- socialUserRepo.getByUser(request.user.id.get)
        invite <- invitationRepo.getByRecipientSocialUserId(su.id.get) if (invite.state != InvitationStates.JOINED)
        senderUserId <- {
          invitationRepo.save(invite.withState(InvitationStates.JOINED))
          invite.senderUserId
        }
      } yield senderUserId
    }
    SafeFuture {
      userCommander.tellAllFriendsAboutNewUser(request.user.id.get, toBeNotified.toSet.toSeq)
      // Temporary event for debugging purpose
      val context = new HeimdalContextBuilder()
      context.addRequestInfo(request)
      heimdalServiceClient.trackEvent(UserEvent(request.user.id.get, context.build, EventType("loaded_install_page")))
    }
    setHasSeenInstall()
    Ok(views.html.website.install(request.user))
  }

  // todo: move this to UserController
  def disconnect(networkString: String) = HtmlAction.authenticated { implicit request =>
    val (suiOpt, code) = userCommander.disconnect(request.userId, networkString)
    suiOpt match {
      case None => code match {
        case "no_other_connected_network" => BadRequest("You must have at least one other network connected.")
        case "not_connected_to_network"   => BadRequest(s"You are not connected to ${networkString}.")
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
