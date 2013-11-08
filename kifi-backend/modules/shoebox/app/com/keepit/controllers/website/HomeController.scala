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
import com.keepit.social.{SocialNetworkType, SocialGraphPlugin}

import ActionAuthenticator.MaybeAuthenticatedRequest
import play.api.Play.current
import play.api._
import play.api.libs.iteratee.Enumerator
import play.api.mvc._
import com.keepit.commanders.InviteCommander
import com.keepit.common.db.ExternalId

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
  inviteCommander: InviteCommander)
  extends WebsiteController(actionAuthenticator) with Logging {

  private def hasSeenInstall(implicit request: AuthenticatedRequest[_]): Boolean = {
    db.readOnly { implicit s => userValueRepo.getValue(request.userId, "has_seen_install").exists(_.toBoolean) }
  }

  private def setHasSeenInstall()(implicit request: AuthenticatedRequest[_]): Unit = {
    db.readWrite { implicit s => userValueRepo.setValue(request.userId, "has_seen_install", true.toString) }
  }

  private def newSignup()(implicit request: Request[_]) =
    request.cookies.get("QA").isDefined || current.configuration.getBoolean("newSignup").getOrElse(false)

  def version = Action {
    Ok(fortyTwoServices.currentVersion.toString)
  }

  def home = HtmlAction(true)(authenticatedAction = { implicit request =>
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
  }, unauthenticatedAction = { implicit request =>
    if (newSignup && request.identityOpt.isDefined) {
      // User needs to sign up or (social) finalize
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else {
      // Non-user landing page
      Ok(views.html.website.welcome(newSignup = newSignup, msg = request.flash.get("error")))
    }
  })

  def curtainHome = Action {
    Ok(views.html.auth.auth()).withCookies(Cookie("QA", "1"))
  }

  def kifiSiteRedirect(path: String) = Action {
    MovedPermanently(s"/$path")
  }

  def homeWithParam(id: String) = home

  def pendingHome()(implicit request: AuthenticatedRequest[AnyContent]) = {
    val user = request.user

    inviteCommander.markPendingInvitesAsAccepted(user.id.get, request.cookies.get("inv").map(v => ExternalId[Invitation](v.name)))

    val (email, friendsOnKifi) = db.readOnly { implicit session =>
      val email = emailRepo.getByUser(user.id.get).sortBy(a => a.verifiedAt.getOrElse(a.createdAt.minusYears(10)).getMillis).lastOption.map(_.address)
      val friendsOnKifi = userConnectionRepo.getConnectedUsers(user.id.get).map { u =>
        val user = userRepo.get(u)
        if(user.state == UserStates.ACTIVE) Some(user.externalId)
        else None
      }.flatten

      (email, friendsOnKifi)
    }
    if (newSignup) {
      Ok(views.html.website.onboarding.userRequestReceived2(
        user = user,
        email = email,
        justVerified = request.queryString.get("m").map(_.headOption == Some("1")).getOrElse(false),
        friendsOnKifi = friendsOnKifi))
    } else {
      Ok(views.html.website.onboarding.userRequestReceived(user, email, friendsOnKifi))
    }
  }

  def install = AuthenticatedHtmlAction { implicit request =>
    db.readWrite { implicit session =>
      socialUserRepo.getByUser(request.user.id.get) map { su =>
        invitationRepo.getByRecipient(su.id.get) match {
          case Some(invite) =>
            if (invite.state != InvitationStates.JOINED) {
              invitationRepo.save(invite.withState(InvitationStates.JOINED))
              invite.senderUserId match {
                case Some(senderUserId) =>
                  for (address <- emailAddressRepo.getByUser(senderUserId)) {
                    postOffice.sendMail(ElectronicMail(
                      senderUserId = None,
                      from = EmailAddresses.CONGRATS,
                      fromName = Some("KiFi Team"),
                      to = List(address),
                      subject = s"${request.user.firstName} ${request.user.lastName} joined KiFi!",
                      htmlBody = views.html.email.invitationFriendJoined(request.user).body,
                      category = PostOffice.Categories.INVITATION))
                  }
                case None =>
              }
            }
          case None =>
        }
      }
    }
    setHasSeenInstall()
    if (newSignup) {
      Ok(views.html.website.install2(request.user))
    } else {
      Ok(views.html.website.install(request.user))
    }
  }

  def disconnect(networkString: String) = AuthenticatedHtmlAction { implicit request =>
    val network = SocialNetworkType(networkString)
    val (thisNetwork, otherNetworks) = db.readOnly { implicit s =>
      socialUserRepo.getByUser(request.userId).partition(_.networkType == network)
    }
    if (otherNetworks.isEmpty) {
      BadRequest("You must have at least one other network connected.")
    } else if (thisNetwork.isEmpty) {
      BadRequest(s"You are not connected to ${network.displayName}.")
    } else {
      val sui = thisNetwork.head
      socialGraphPlugin.asyncRevokePermissions(sui)
      db.readWrite { implicit s =>
        socialConnectionRepo.deactivateAllConnections(sui.id.get)
        socialUserRepo.invalidateCache(sui)
        socialUserRepo.save(sui.copy(credentials = None, userId = None))
      }
      otherNetworks map socialGraphPlugin.asyncFetch
      Redirect(com.keepit.controllers.website.routes.HomeController.home())
    }
  }

  def gettingStarted = AuthenticatedHtmlAction { implicit request =>
    if (newSignup) {
      Ok(views.html.website.gettingStarted2(request.user))
    } else {
      Ok(views.html.website.gettingStarted(request.user))
    }
  }
}
