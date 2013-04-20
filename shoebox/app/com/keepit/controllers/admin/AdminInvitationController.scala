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
    val (invitesWithSocial, count) = db.readOnly { implicit session =>
      val count = invitationRepo.count
      val invitesWithSocial = invitationRepo.page(page, pageSize) map { invite =>
        (invite, socialUserRepo.get(invite.recipientSocialUserId), userRepo.get(invite.senderUserId))
      }
      (invitesWithSocial, count)
    }
    val numPages = (count / pageSize).toInt
    Ok(html.admin.invitationsDisplay(invitesWithSocial, page, count, numPages, showing))
  }

//  def addPhrase = AdminHtmlAction{ implicit request =>
//    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
//    val phrase = body.get("phrase").get
//    val lang = body.get("lang").get
//    val source = body.get("source").get
//
//    db.readWrite { implicit session =>
//      phraseRepo.save(Phrase(phrase = phrase, lang = Lang(lang), source = source))
//    }
//    Redirect(com.keepit.controllers.admin.routes.PhraseController.displayPhrases())
//  }

  def acceptUser(id: Id[SocialUserInfo]) = AdminHtmlAction { implicit request =>
    val result = db.readWrite { implicit session =>
      val socialUser = socialUserRepo.get(id)
      val userOpt = socialUser.userId.map(userRepo.get)
      val inviteOpt = invitationRepo.getByRecipient(id)
      for(user <- userOpt; invite <- inviteOpt) yield {
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
  
  private def notifyAcceptedUser(userId: Id[User]) = {
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
            category = PostOffice.Categories.COMMENT))
        }
    }
  }
}
