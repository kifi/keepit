package com.keepit.shoebox


import com.keepit.common.db.slick.RepoModification
import com.keepit.model.{
  SocialConnection,
  UserConnection,
  Invitation,
  SocialUserInfo,
  EmailAddress
}

import com.keepit.commanders.{
  ShoeboxRichConnectionCommander,
  SocialConnectionModification,
  SocialConnectionModificationActor,
  UserConnectionModification,
  UserConnectionModificationActor,
  InvitationModification,
  InvitationModificationActor,
  SocialUserInfoModification,
  SocialUserInfoModificationActor,
  EmailAddressModificationActor,
  EmailAddressModification
}

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.queue.RecordInvitation

case class ShoeboxRepoChangeListenerModule() extends ScalaModule {
  def configure(): Unit = {}

  @Provides
  @Singleton
  def socialConnectionChangeListener(socialConnectionModificationActor: ActorInstance[SocialConnectionModificationActor]): Option[RepoModification.Listener[SocialConnection]] = Some(
    repoModification => socialConnectionModificationActor.ref ! SocialConnectionModification(repoModification)
  )

  @Provides
  @Singleton
  def userConnectionChangeListener(userConnectionModificationActor: ActorInstance[UserConnectionModificationActor]): Option[RepoModification.Listener[UserConnection]] = Some({
    repoModification => userConnectionModificationActor.ref ! UserConnectionModification(repoModification)
  })

  @Provides
  @Singleton
  def invitationChangeListener(invitationModificationActor: ActorInstance[InvitationModificationActor], shoeboxRichConnectionCommander: ShoeboxRichConnectionCommander): Option[RepoModification.Listener[Invitation]] = Some({
    repoModification => {
      val invitation = repoModification.model
      invitation.senderUserId.foreach { userId =>
        shoeboxRichConnectionCommander.processUpdateImmediate(RecordInvitation(userId, invitation.id.get, invitation.recipientSocialUserId, invitation.recipientEContactId))
      }
      invitationModificationActor.ref ! InvitationModification(repoModification)
    }
  })

  @Provides
  @Singleton
  def socialUserChangeListener(socialUserInfoMoficationActor: ActorInstance[SocialUserInfoModificationActor]): Option[RepoModification.Listener[SocialUserInfo]] = Some({
    repoModification => socialUserInfoMoficationActor.ref ! SocialUserInfoModification(repoModification)
  })

  @Provides
  @Singleton
  def emailAddressChangeListener(emailAddressMoficationActor: ActorInstance[EmailAddressModificationActor]): Option[RepoModification.Listener[EmailAddress]] = Some({
    repoModification => emailAddressMoficationActor.ref ! EmailAddressModification(repoModification)
  })

}

case class FakeShoeboxRepoChangeListenerModule() extends ScalaModule {
  def configure(): Unit = {}

  @Provides
  @Singleton
  def socialConnectionChangeListener(): Option[RepoModification.Listener[SocialConnection]] = None

  @Provides
  @Singleton
  def userConnectionChangeListener(): Option[RepoModification.Listener[UserConnection]] = None

  @Provides
  @Singleton
  def invitationChangeListener(): Option[RepoModification.Listener[Invitation]] = None

  @Provides
  @Singleton
  def socialUserChangeListener(): Option[RepoModification.Listener[SocialUserInfo]] = None

  @Provides
  @Singleton
  def emailAddressChangeListener(): Option[RepoModification.Listener[EmailAddress]] = None
}
