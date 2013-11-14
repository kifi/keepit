package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.InvitationMailPlugin
import com.keepit.model._

import views.html

class AdminInvitationController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  invitationRepo: InvitationRepo,
  socialUserRepo: SocialUserInfoRepo,
  invitationMailPlugin: InvitationMailPlugin,
  userRepo: UserRepo)
    extends AdminController(actionAuthenticator) {

  val pageSize = 50

  def displayInvitations(page: Int = 0, showing: String = "all") = AdminHtmlAction{ implicit request =>
    val showState = showing.toLowerCase match {
      case InvitationStates.ACCEPTED.value => Some(InvitationStates.ACCEPTED)
      case InvitationStates.ADMIN_ACCEPTED.value => Some(InvitationStates.ADMIN_ACCEPTED)
      case InvitationStates.ADMIN_REJECTED.value => Some(InvitationStates.ADMIN_REJECTED)
      case InvitationStates.JOINED.value => Some(InvitationStates.JOINED)
      case InvitationStates.ACTIVE.value => Some(InvitationStates.ACTIVE)
      case InvitationStates.INACTIVE.value => Some(InvitationStates.INACTIVE)
      case "all" => None
    }
    val (invitesWithSocial, count) = db.readOnly { implicit session =>
      val count = invitationRepo.count
      val invitesWithSocial = invitationRepo.invitationsPage(page, pageSize, showState) map {
        case (invite, sui) => (invite.map(i => (i, i.senderUserId.map(userRepo.get))), sui)
      }
      (invitesWithSocial, count)
    }
    val numPages = (count / pageSize)
    Ok(html.admin.invitationsDisplay(invitesWithSocial, page, count, numPages, showing))
  }

  def acceptUser(id: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val result = db.readWrite { implicit session =>
      val socialUser = socialUserRepo.get(id)
      for (user <- socialUser.userId.map(userRepo.get)) yield {
        val invite = invitationRepo.getByRecipientSocialUserId(id).getOrElse(invitationRepo.save(Invitation(
          createdAt = user.createdAt,
          senderUserId = None,
          recipientSocialUserId = socialUser.id
        )))
        (userRepo.save(user.withState(UserStates.ACTIVE)),
          invitationRepo.save(invite.withState(InvitationStates.ADMIN_ACCEPTED)))
      }
    }

    if(result.isDefined) {
      notifyAcceptedUser(result.get._1.id.get)
      Redirect(routes.AdminInvitationController.displayInvitations())
    } else {
      Redirect(routes.AdminInvitationController.displayInvitations()).flashing("error" -> "Invalid!")
    }
  }

  def rejectUser(id: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val result = db.readWrite { implicit session =>
      val socialUser = socialUserRepo.get(id)
      for (user <- socialUser.userId.map(userRepo.get)) yield {
        val invite = invitationRepo.getByRecipientSocialUserId(id).getOrElse(invitationRepo.save(Invitation(
          createdAt = user.createdAt,
          senderUserId = None,
          recipientSocialUserId = socialUser.id
        )))
        (user, invitationRepo.save(invite.withState(InvitationStates.ADMIN_REJECTED)))
      }
    }

    if (result.isDefined) {
      Redirect(routes.AdminInvitationController.displayInvitations())
    } else {
      Redirect(routes.AdminInvitationController.displayInvitations()).flashing("error" -> "Invalid!")
    }
  }

  private def notifyAcceptedUser(userId: Id[User]) {
    invitationMailPlugin.notifyAcceptedUser(userId)
  }
}
