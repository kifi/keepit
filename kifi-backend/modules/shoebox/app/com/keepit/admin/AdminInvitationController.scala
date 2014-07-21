package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.commanders.UserCommander

import views.html

class AdminInvitationController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    invitationRepo: InvitationRepo,
    socialUserRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    userCommander: UserCommander) extends AdminController(actionAuthenticator) {

  val pageSize = 50

  def displayInvitations(page: Int = 0, showing: String = "all") = AdminHtmlAction.authenticated { implicit request =>
    val showState = showing.toLowerCase match {
      case InvitationStates.ACCEPTED.value => Some(InvitationStates.ACCEPTED)
      case InvitationStates.ADMIN_ACCEPTED.value => Some(InvitationStates.ADMIN_ACCEPTED)
      case InvitationStates.ADMIN_REJECTED.value => Some(InvitationStates.ADMIN_REJECTED)
      case InvitationStates.JOINED.value => Some(InvitationStates.JOINED)
      case InvitationStates.ACTIVE.value => Some(InvitationStates.ACTIVE)
      case InvitationStates.INACTIVE.value => Some(InvitationStates.INACTIVE)
      case "all" => None
    }
    val (invitesWithSocial, count) = db.readOnlyReplica { implicit session =>
      val count = invitationRepo.count
      val invitesWithSocial = invitationRepo.invitationsPage(page, pageSize, showState) map {
        case (invite, sui) => (invite.map(i => (i, i.senderUserId.map(userRepo.get))), sui)
      }
      (invitesWithSocial, count)
    }
    val numPages = (count / pageSize)
    Ok(html.admin.invitationsDisplay(invitesWithSocial, page, count, numPages, showing))
  }

  def acceptUser(id: Id[SocialUserInfo]) = AdminHtmlAction.authenticated { implicit request =>
    val result = db.readWrite { implicit session =>
      val socialUser = socialUserRepo.get(id)
      for (user <- socialUser.userId.map(userRepo.get)) yield {
        userRepo.save(user.withState(UserStates.ACTIVE))

        val existingInvites = socialUserRepo.getByUser(user.id.get).map { sui =>
          invitationRepo.getByRecipientSocialUserId(sui.id.get) map { invite =>
            invitationRepo.save(invite.withState(InvitationStates.ADMIN_ACCEPTED))
          }
        }.flatten

        val newInvite = if (existingInvites.isEmpty) {
          Seq(invitationRepo.save(Invitation(
            createdAt = user.createdAt,
            senderUserId = None,
            recipientSocialUserId = socialUser.id,
            state = InvitationStates.ADMIN_ACCEPTED
          )))
        } else Nil

        user
      }
    }

    if (result.isDefined) {
      notifyAcceptedUser(result.get)
      Redirect(routes.AdminInvitationController.displayInvitations())
    } else {
      Redirect(routes.AdminInvitationController.displayInvitations()).flashing("error" -> "Invalid!")
    }
  }

  def rejectUser(id: Id[SocialUserInfo]) = AdminHtmlAction.authenticated { implicit request =>
    val rejectedUser = db.readWrite { implicit session =>
      val socialUser = socialUserRepo.get(id)
      for (user <- socialUser.userId.map(userRepo.get)) yield {
        val invites = invitationRepo.getByRecipientSocialUserId(id) match {
          case Seq() => Seq(Invitation(
            createdAt = user.createdAt,
            senderUserId = None,
            recipientSocialUserId = socialUser.id
          ))
          case invites => invites
        }
        invites.foreach(invite => invitationRepo.save(invite.withState(InvitationStates.ADMIN_REJECTED)))
        user
      }
    }

    if (rejectedUser.isDefined) {
      Redirect(routes.AdminInvitationController.displayInvitations())
    } else {
      Redirect(routes.AdminInvitationController.displayInvitations()).flashing("error" -> "Invalid!")
    }
  }

  private def notifyAcceptedUser(user: User): Unit = {
    userCommander.sendWelcomeEmail(user)
  }
}
