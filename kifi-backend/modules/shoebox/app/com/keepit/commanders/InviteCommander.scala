package com.keepit.commanders

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
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.RWSession

class InviteCommander @Inject() (
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
  clock: Clock) {

  def getPendingInvitesForUser(userId: Id[User]) = {
    db.readOnly { implicit s =>
      socialUserRepo.getByUser(userId) map { su =>
        su -> invitationRepo.getByRecipient(su.id.get).getOrElse(Invitation(
          createdAt = clock.now,
          senderUserId = None,
          recipientSocialUserId = su.id.get,
          state = InvitationStates.ACTIVE
        ))
      }
    }
  }

  def markPendingInvitesAsAccepted(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    val anyPendingInvites = getPendingInvitesForUser(userId)
    db.readWrite { implicit s =>
      for ((su, invite) <- anyPendingInvites) {
        connectInvitedUsers(userId, invite)
        invitationRepo.save(invite.copy(state = InvitationStates.ACCEPTED))
        if (invite.state == InvitationStates.ACTIVE) {
          notifyAdminsAboutNewSignupRequest(userId, su.fullName)
        }
      }
      invId.map { extId =>
        val invite = invitationRepo.get(extId)
        connectInvitedUsers(userId, invite)
        invitationRepo.save(invite.withState(InvitationStates.ACCEPTED))
        if (invite.state == InvitationStates.ACTIVE) {
          val user = userRepo.get(userId)
          notifyAdminsAboutNewSignupRequest(userId, s"${user.firstName} ${user.lastName}")
        }
      }
      socialConnectionRepo
    }
  }

  private def connectInvitedUsers(userId: Id[User], invite: Invitation)(implicit session: RWSession) = {
    invite.senderUserId.map { senderUserId =>
      userConnectionRepo.addConnections(userId, Set(senderUserId), requested = true)
    }
  }

  private def notifyAdminsAboutNewSignupRequest(userId: Id[User], name: String)(implicit session: RWSession) = {
    postOffice.sendMail(ElectronicMail(
      senderUserId = None,
      from = EmailAddresses.NOTIFICATIONS,
      fromName = Some("Invitations"),
      to = List(EmailAddresses.INVITATION),
      subject = s"""${name} wants to be let in!""",
      htmlBody = s"""<a href="https://admin.kifi.com/admin/user/${userId}">${name}</a> wants to be let in!\n<br/>
                           Go to the <a href="https://admin.kifi.com/admin/invites?show=accepted">admin invitation page</a> to accept or reject this user.""",
      category = PostOffice.Categories.ADMIN))

  }
}
