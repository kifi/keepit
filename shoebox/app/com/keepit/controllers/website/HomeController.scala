package com.keepit.controllers.website

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.State
import com.keepit.common.db.slick._
import com.keepit.common.mail.{EmailAddresses, ElectronicMail, PostOffice}
import com.keepit.model._

import play.api.Play.current
import play.api._
import play.api.mvc._

@Singleton
class HomeController @Inject() (db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailRepo: EmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator,
  postOffice: PostOffice)
    extends WebsiteController(actionAuthenticator) {

  def home = HtmlAction(true)(authenticatedAction = { implicit request =>

    if(request.user.state == UserStates.PENDING) { pendingHome() }
    else {
      val friendsOnKifi = db.readOnly { implicit session =>
        socialConnectionRepo.getFortyTwoUserConnections(request.user.id.get).map { u =>
          val user = userRepo.get(u)
          if(user.state == UserStates.ACTIVE) Some(user.externalId)
          else None
        } flatten
      }

      val userCanInvite = request.experimants.contains(ExperimentTypes.ADMIN) || request.experimants.contains(ExperimentTypes.CAN_INVITE)
      
      Ok(views.html.website.userHome(request.user, friendsOnKifi, userCanInvite))
    }
  }, unauthenticatedAction = { implicit request =>
    Ok(views.html.website.welcome())
  })

  def pendingHome()(implicit request: AuthenticatedRequest[AnyContent]) = {
    val user = request.user
    val anyPendingInvite = db.readOnly { implicit s =>
      socialUserRepo.getByUser(user.id.get) map { su =>
        su -> invitationRepo.getByRecipient(su.id.get).getOrElse(Invitation(
          createdAt = user.createdAt,
          senderUserId = None,
          recipientSocialUserId = su.id.get,
          state = InvitationStates.ACTIVE
        ))
      }
    }
    for ((su, invite) <- anyPendingInvite) {
      if (invite.state == InvitationStates.ACTIVE) {
        db.readWrite { implicit s =>
          invitationRepo.save(invite.copy(state = InvitationStates.ACCEPTED))
          postOffice.sendMail(ElectronicMail(
            senderUserId = None,
            from = EmailAddresses.NOTIFICATIONS,
            fromName = Some("Invitations"),
            to = EmailAddresses.INVITATION,
            subject = s"${su.fullName} wants to be let in!",
            htmlBody = s"Go to https://admin.kifi.com/admin/invites to accept or reject this user.",
            category = PostOffice.Categories.ADMIN))
        }
      }
    }
    val (email, friendsOnKifi) = db.readOnly { implicit session =>
      val email = emailRepo.getByUser(user.id.get).headOption.map(_.address)
      val friendsOnKifi = socialConnectionRepo.getFortyTwoUserConnections(user.id.get).map { u =>
        val user = userRepo.get(u)
        if(user.state == UserStates.ACTIVE) Some(user.externalId)
        else None
      } flatten

      (email, friendsOnKifi)
    }
    Ok(views.html.website.onboarding.userRequestReceived(user, email, friendsOnKifi))
  }

  def install = AuthenticatedHtmlAction { implicit request =>
    db.readWrite { implicit session =>
      socialUserRepo.getByUser(request.user.id.get) map { su =>
        invitationRepo.getByRecipient(su.id.get) match {
          case Some(invite) =>
            invitationRepo.save(invite.withState(InvitationStates.JOINED))
          case None =>
        }
      }
    }
    Ok(views.html.website.install(request.user))
  }

  def gettingStarted = AuthenticatedHtmlAction { implicit request =>
    Ok(views.html.website.gettingStarted(request.user))
  }
}
