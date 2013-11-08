package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.db.slick._
import com.keepit.common.mail.EmailAddresses
import com.keepit.common.mail.{ElectronicMail, PostOffice, LocalPostOffice}
import com.keepit.model._
import com.keepit.social.SocialNetworks

import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.RWSession

class InviteCommander @Inject() (
  db: Database,
  userRepo: UserRepo,
  socialUserRepo: SocialUserInfoRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator,
  postOffice: LocalPostOffice,
  emailAddressRepo: EmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  clock: Clock) {

  def getOrCreatePendingInvitesForUser(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    db.readOnly { implicit s =>
      val cookieInvite = invId.map { inviteExtId =>
        val invite = invitationRepo.get(inviteExtId)
        // todo: When invite.recipientSocialUserId is an Option, check here if it's set. If not, set it on the invite record.
        socialUserRepo.get(invite.recipientSocialUserId) -> invite
      }

      val userSocialAccounts = socialUserRepo.getByUser(userId)
      val existingInvites = userSocialAccounts.map { su =>
        invitationRepo.getByRecipient(su.id.get).map { inv => su -> inv }
      }.flatten.toSet.++(cookieInvite)

      if (existingInvites.isEmpty) {
        userSocialAccounts.find(_.networkType == SocialNetworks.FORTYTWO).map { su =>
          Set(su -> Invitation(
            createdAt = clock.now,
            senderUserId = None,
            recipientSocialUserId = su.id.get,
            state = InvitationStates.ACTIVE
          ))
        }.getOrElse(Set())
      } else {
        existingInvites
      }
    }
  }

  def markPendingInvitesAsAccepted(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    val anyPendingInvites = getOrCreatePendingInvitesForUser(userId, invId)
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
      val newFortyTwoSocialUser = socialUserRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO)
      val inviterFortyTwoSocialUser = socialUserRepo.getByUser(senderUserId).find(_.networkType == SocialNetworks.FORTYTWO)
      for {
        su1 <- newFortyTwoSocialUser
        su2 <- inviterFortyTwoSocialUser
      } yield {
        socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su2.id.get))
      }
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
