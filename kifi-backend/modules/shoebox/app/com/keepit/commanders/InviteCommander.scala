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
import scala.util.Try

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

  def getOrCreateInvitesForUser(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    db.readOnly { implicit s =>
      val userSocialAccounts = socialUserRepo.getByUser(userId)
      val cookieInvite = invId.flatMap { inviteExtId =>
        Try(invitationRepo.get(inviteExtId)).map { invite =>
          val invitedSocial = userSocialAccounts.find(_.id.get == invite.recipientSocialUserId)
          val detectedInvite = if (invitedSocial.isEmpty && userSocialAccounts.nonEmpty) {
            // User signed up using a different social account than what we know about.
            invite.copy(recipientSocialUserId = userSocialAccounts.head.id)
          } else {
            invite
          }
          // todo: When invite.recipientSocialUserId is an Option, check here if it's set. If not, set it on the invite record.
          invite.recipientSocialUserId match {
            case Some(rsui) => socialUserRepo.get(rsui) -> detectedInvite
            case None => socialUserRepo.get(userSocialAccounts.head.id.get) -> detectedInvite
          }
        }.toOption
      }

      val existingInvites = userSocialAccounts.map { su =>
        invitationRepo.getByRecipient(su.id.get).map { inv =>
          su -> inv
        }
      }.flatten.toSet.++(cookieInvite)

      if (existingInvites.isEmpty) {
        userSocialAccounts.find(_.networkType == SocialNetworks.FORTYTWO).map { su =>
          Set(su -> Invitation(
            createdAt = clock.now,
            senderUserId = None,
            recipientSocialUserId = su.id,
            state = InvitationStates.ACTIVE
          ))
        }.getOrElse(Set())
      } else {
        existingInvites
      }
    }
  }

  def markPendingInvitesAsAccepted(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    val anyPendingInvites = getOrCreateInvitesForUser(userId, invId).filter { case (_, in) =>
      Set(InvitationStates.INACTIVE, InvitationStates.ACTIVE).contains(in.state)
    }
    db.readWrite { implicit s =>
      for ((su, invite) <- anyPendingInvites) {
        connectInvitedUsers(userId, invite)
        if (Set(InvitationStates.INACTIVE, InvitationStates.ACTIVE).contains(invite.state)) {
          invitationRepo.save(invite.copy(state = InvitationStates.ACCEPTED))
          notifyAdminsAboutNewSignupRequest(userId, su.fullName)
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
        socialConnectionRepo.getConnectionOpt(su1.id.get, su2.id.get) match {
          case Some(sc) =>
            socialConnectionRepo.save(sc.withState(SocialConnectionStates.ACTIVE))
          case None =>
            socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su2.id.get, state = SocialConnectionStates.ACTIVE))
        }
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
