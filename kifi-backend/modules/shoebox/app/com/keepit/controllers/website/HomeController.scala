package com.keepit.controllers.website

import com.keepit.common.controller.{ActionAuthenticator, AuthenticatedRequest, WebsiteController}
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
  inviteCommander: InviteCommander)
  extends WebsiteController(actionAuthenticator) with Logging {

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

  // temp! if I'm still here post-launch, sack whoever is responsible for things around here.
  def newDesignCookie = Action { request =>
    Ok.withCookies(Cookie("newdesign","yep"))
  }


  def newHome = HtmlAction(true)(authenticatedAction = homeAuthed(_), unauthenticatedAction = newHomeNotAuthed(_))

  private def newHomeNotAuthed(implicit request: Request[_]): Result = {
    if (request.identityOpt.isDefined) {
      // User needs to sign up or (social) finalize
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else {
      // TODO: Redirect to /login if the path is not /
      // Non-user landing page
      Ok(views.html.marketing.landing())
    }
  }

  def about = HtmlAction(true)(authenticatedAction = aboutHandler(isLoggedIn = true)(_), unauthenticatedAction = aboutHandler(isLoggedIn = false)(_))
  private def aboutHandler(isLoggedIn: Boolean)(implicit request: Request[_]): Result = {
    Ok(views.html.marketing.about(isLoggedIn))
  }

  def newTerms = HtmlAction(true)(authenticatedAction = termsHandler(isLoggedIn = true)(_), unauthenticatedAction = termsHandler(isLoggedIn = false)(_))
  private def termsHandler(isLoggedIn: Boolean)(implicit request: Request[_]): Result = {
    Ok(views.html.marketing.terms(isLoggedIn))
  }

  def newPrivacy = HtmlAction(true)(authenticatedAction = privacyHandler(isLoggedIn = true)(_), unauthenticatedAction = privacyHandler(isLoggedIn = false)(_))
  private def privacyHandler(isLoggedIn: Boolean)(implicit request: Request[_]): Result = {
    Ok(views.html.marketing.privacy(isLoggedIn))
  }

  def mobileLanding = HtmlAction(true)(authenticatedAction = mobileLandingHandler(isLoggedIn = true)(_), unauthenticatedAction = mobileLandingHandler(isLoggedIn = false)(_))
  private def mobileLandingHandler(isLoggedIn: Boolean)(implicit request: Request[_]): Result = {
    Ok(views.html.marketing.mobileLanding(isLoggedIn, "iphone"))
  }
  // End post-launch stuff!

  def home = HtmlAction(true)(authenticatedAction = homeAuthed(_), unauthenticatedAction = homeNotAuthed(_))

  private def homeAuthed(implicit request: AuthenticatedRequest[_]): Result = {
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

  private def homeNotAuthed(implicit request: Request[_]): Result = {
    if (request.identityOpt.isDefined) {
      // User needs to sign up or (social) finalize
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else {
      // TODO: Redirect to /login if the path is not /
      // Non-user landing page
      if(request.cookies.get("newdesign").isDefined) {
        log.info(request.headers.toSimpleMap.toString)
        val agentOpt = request.headers.get("User-Agent").map { agent =>
          UserAgent.fromString(agent)
        }
        if (agentOpt.map(_.isMobile).isDefined) {
          val ua = agentOpt.get.userAgent
          val isIphone = ua.contains("iPhone") && !ua.contains("iPad")
          val agentClass = if (isIphone) "iphone" else ""
          Ok(views.html.marketing.mobileLanding(false, agentClass))
        } else {
          Ok(views.html.marketing.landing())
        }
      } else {
        Ok(views.html.auth.auth())
      }
    }
  }

  def homeWithParam(id: String) = home

  def blog = HtmlAction(true)(authenticatedAction = { request =>
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

  def install = AuthenticatedHtmlAction { implicit request =>
    db.readWrite { implicit session =>
      val toBeNotified = for {
        su <- socialUserRepo.getByUser(request.user.id.get)
        invite <- invitationRepo.getByRecipientSocialUserId(su.id.get) if (invite.state != InvitationStates.JOINED)
        senderUserId <- {
          invitationRepo.save(invite.withState(InvitationStates.JOINED))
          invite.senderUserId
        }
      } yield senderUserId
      SafeFuture { userCommander.tellAllFriendsAboutNewUser(request.user.id.get, toBeNotified.toSet.toSeq) }
    }
    setHasSeenInstall()
    Ok(views.html.website.install(request.user))
  }

  // todo: move this to UserController
  def disconnect(networkString: String) = AuthenticatedHtmlAction { implicit request =>
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

  // Do not remove until a while past Jan 7 2014. The extension sends users to this URL after installation.
  def gettingStarted = Action { request =>
    Redirect("/")
  }

  def termsOfService = Action { implicit request =>
    Ok(views.html.website.termsOfService())
  }

  def privacyPolicy = Action { implicit request =>
    Ok(views.html.website.privacyPolicy())
  }

}
