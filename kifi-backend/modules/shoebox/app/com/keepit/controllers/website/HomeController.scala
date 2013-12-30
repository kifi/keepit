package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddresses
import com.keepit.common.mail.{ElectronicMail, PostOffice, LocalPostOffice}
import com.keepit.common.service.FortyTwoServices
import com.keepit.controllers.core.AuthController
import com.keepit.model._
import com.keepit.social.{SocialNetworks, SocialNetworkType, SocialGraphPlugin}

import ActionAuthenticator.MaybeAuthenticatedRequest
import play.api.Play.current
import play.api._
import play.api.http.HeaderNames.USER_AGENT
import play.api.libs.iteratee.Enumerator
import play.api.mvc._
import com.keepit.commanders.{UserCommander, InviteCommander}
import com.keepit.common.db.ExternalId
import securesocial.core.{SecureSocial, Authenticator}

class HomeController @Inject() (
  db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator,
  postOffice: LocalPostOffice,
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
      Ok(views.html.auth.auth())
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
      socialUserRepo.getByUser(request.user.id.get) map { su =>
        invitationRepo.getByRecipientSocialUserId(su.id.get).map { invite =>
          if (invite.state != InvitationStates.JOINED) {
            invitationRepo.save(invite.withState(InvitationStates.JOINED))
            invite.senderUserId.map { senderUserId =>
              for (address <- emailAddressRepo.getAllByUser(senderUserId)) {
                postOffice.sendMail(ElectronicMail(
                  senderUserId = None,
                  from = EmailAddresses.CONGRATS,
                  fromName = Some("KiFi Team"),
                  to = List(address),
                  subject = s"${request.user.firstName} ${request.user.lastName} joined KiFi!",
                  htmlBody = views.html.email.invitationFriendJoined(request.user).body,
                  category = PostOffice.Categories.User.INVITATION))
              }
            }
          }
        }
      }
    }
    setHasSeenInstall()
    Ok(views.html.website.install2(request.user))
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

  def gettingStarted = AuthenticatedHtmlAction { implicit request =>
    Ok(views.html.website.gettingStarted2(request.user))
  }

  def redditPreview = Action { implicit request =>
    Ok(views.html.website.redditPreview())
  }

  def termsOfService = Action { implicit request =>
    Ok(views.html.website.termsOfService())
  }

  def privacyPolicy = Action { implicit request =>
    Ok(views.html.website.privacyPolicy())
  }

}
