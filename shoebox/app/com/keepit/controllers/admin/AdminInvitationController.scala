package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model.{PhraseStates, PhraseRepo, Phrase}
import com.keepit.search.phrasedetector.PhraseImporter
import com.keepit.search.{SearchServiceClient, Lang}
import views.html
import com.keepit.model._
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.EmailAddresses

@Singleton
class AdminInvitationController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  invitationRepo: InvitationRepo,
  socialUserRepo: SocialUserInfoRepo,
  emailAddressRepo: EmailAddressRepo,
  postOffice: PostOffice,
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
        val invite = invitationRepo.getByRecipient(id).getOrElse(invitationRepo.save(Invitation(
          createdAt = user.createdAt,
          senderUserId = None,
          recipientSocialUserId = socialUser.id.get
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
        val invite = invitationRepo.getByRecipient(id).getOrElse(invitationRepo.save(Invitation(
          createdAt = user.createdAt,
          senderUserId = None,
          recipientSocialUserId = socialUser.id.get
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
    db.readOnly { implicit session =>
      val user = userRepo.get(userId)
      val addrs = emailAddressRepo.getByUser(userId)
      for(address <- addrs) {
        postOffice.sendMail(ElectronicMail(
            senderUserId = None,
            from = EmailAddresses.CONGRATS,
            fromName = Some("KiFi Team"),
            to = address,
            subject = "Congrats! You're in the KiFi Private Beta",
            htmlBody = views.html.email.invitationAccept(user).body,
            category = PostOffice.Categories.INVITATION))
        }
    }
  }
}
