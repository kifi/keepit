package com.keepit.commanders

import com.keepit.abook.ABookServiceClient
import com.keepit.common.queue._
import com.keepit.model._
import com.keepit.common.db.slick.{RepoModification, Database}
import com.keepit.common.logging.Logging

import com.kifi.franz.SQSQueue

import com.google.inject.{Inject, Singleton, Provider}
import com.keepit.common.actor.{BatchingActor, BatchingActorConfiguration}
import scala.concurrent.duration._
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.reflect.ClassTag
import com.keepit.common.db.{Id, SequenceNumber, Model}
import akka.actor.Scheduler
import com.keepit.common.time.Clock
import com.keepit.model.SocialConnection
import com.keepit.common.zookeeper.ServiceDiscovery

@Singleton
class ShoeboxRichConnectionCommander @Inject() (
    abook: ABookServiceClient,
    queue: SQSQueue[RichConnectionUpdateMessage],
    socialConnectionRepo: Provider[SocialConnectionRepo],
    userConnectionRepo: Provider[UserConnectionRepo],
    invitationRepo: Provider[InvitationRepo],
    socialUserInfoRepo: Provider[SocialUserInfoRepo],
    emailAddressRepo: Provider[UserEmailAddressRepo],
    systemValueRepo: SystemValueRepo,
    db: Database,
    serviceDiscovery: ServiceDiscovery
) extends RemoteRichConnectionCommander(abook, queue) with Logging {

  private val sqsSocialConnectionSeq = Name[SequenceNumber[SocialConnection]]("sqs_social_connection")
  private val sqsUserConnectionSeq = Name[SequenceNumber[UserConnection]]("sqs_user_connection")
  private val sqsSocialUserInfoSeq = Name[SequenceNumber[SocialUserInfo]]("sqs_social_user_info")
  private val sqsInvitationSeq = Name[SequenceNumber[Invitation]]("sqs_invitation")
  private val sqsEmailAddressSeq = Name[SequenceNumber[UserEmailAddress]]("sqs_email_address")

  def sendSocialConnections(maxBatchSize: Int): Int = if (!serviceDiscovery.isLeader()) 0 else {
    val (updateRichConnections, socialConnectionCount, highestSeq) = db.readOnly() { implicit session =>
      val currentSeq = systemValueRepo.getSequenceNumber(sqsSocialConnectionSeq) getOrElse SequenceNumber.ZERO
      val socialConnections = socialConnectionRepo.get.getBySequenceNumber(currentSeq, maxBatchSize)
      val updateRichConnections = socialConnections.map { case socialConnection =>
        val sUser1 = socialUserInfoRepo.get.get(socialConnection.socialUser1)
        val sUser2 = socialUserInfoRepo.get.get(socialConnection.socialUser2)
        socialConnection.state match {
          case SocialConnectionStates.ACTIVE => InternRichConnection(sUser1, sUser2)
          case SocialConnectionStates.INACTIVE => RemoveRichConnection(sUser1, sUser2)
        }
      }
      val highestSeq = if (socialConnections.nonEmpty) socialConnections.map(_.seq).max else currentSeq
      (updateRichConnections, socialConnections.length, highestSeq)
    }

    if (socialConnectionCount > 0) {
      updateRichConnections.foreach(processUpdate)
      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(sqsSocialConnectionSeq, highestSeq)
      }
    }

    socialConnectionCount
  }

  def sendUserConnections(maxBatchSize: Int): Int = if (!serviceDiscovery.isLeader()) 0 else {
    val userConnections = db.readOnly() { implicit session =>
      val currentSeq = systemValueRepo.getSequenceNumber(sqsUserConnectionSeq) getOrElse SequenceNumber.ZERO
      userConnectionRepo.get.getBySequenceNumber(currentSeq, maxBatchSize)
    }

    if (userConnections.nonEmpty) {
      userConnections.foreach { userConnection =>
        val updateKifiConnection = userConnection.state match {
          case UserConnectionStates.ACTIVE => RecordKifiConnection(userConnection.user1, userConnection.user2)
          case UserConnectionStates.INACTIVE | UserConnectionStates.UNFRIENDED => RemoveKifiConnection(userConnection.user1, userConnection.user2)
        }
        processUpdate(updateKifiConnection)
      }

      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(sqsUserConnectionSeq, userConnections.map(_.seq).max)
      }
    }

    userConnections.length
  }

  def sendSocialUsers(maxBatchSize: Int): Int = if (!serviceDiscovery.isLeader()) 0 else {
    val socialUserInfos = db.readOnly() { implicit session =>
      val currentSeq = systemValueRepo.getSequenceNumber(sqsSocialUserInfoSeq) getOrElse SequenceNumber.ZERO
      socialUserInfoRepo.get.getBySequenceNumber(currentSeq, maxBatchSize)
    }

    if (socialUserInfos.nonEmpty) {
      socialUserInfos.collect { case socialUserInfo if socialUserInfo.state != SocialUserInfoStates.INACTIVE =>
        socialUserInfo.userId.foreach { friendUserId => processUpdate(RecordFriendUserId(socialUserInfo.networkType, socialUserInfo.id, None, friendUserId)) }
      }

      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(sqsSocialUserInfoSeq, socialUserInfos.map(_.seq).max)
      }
    }

    socialUserInfos.length
  }

  def sendInvitations(maxBatchSize: Int): Int = if (!serviceDiscovery.isLeader()) 0 else {
    val invitations = db.readOnly() { implicit session =>
      val currentSeq = systemValueRepo.getSequenceNumber(sqsInvitationSeq) getOrElse SequenceNumber.ZERO
      invitationRepo.get.getBySequenceNumber(currentSeq, maxBatchSize)
    }

    if (invitations.nonEmpty) {
      invitations.foreach {
        case invitation if invitation.state != InvitationStates.INACTIVE => invitation.senderUserId.foreach { userId =>
          processUpdate(RecordInvitation(userId, invitation.recipientSocialUserId, invitation.recipientEmailAddress))
        }
        case inactiveInvitation => inactiveInvitation.senderUserId.foreach { userId =>
          processUpdate(CancelInvitation(userId, inactiveInvitation.recipientSocialUserId, inactiveInvitation.recipientEmailAddress))
        }
      }

      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(sqsInvitationSeq, invitations.map(_.seq).max)
      }
    }

    invitations.length
  }

  def sendEmailAddresses(maxBatchSize: Int): Int = if (!serviceDiscovery.isLeader()) 0 else {
    val emails = db.readOnly() { implicit session =>
      val currentSeq = systemValueRepo.getSequenceNumber(sqsEmailAddressSeq) getOrElse SequenceNumber.ZERO
      emailAddressRepo.get.getBySequenceNumber(currentSeq, maxBatchSize)
    }

    if (emails.nonEmpty) {
      emails.collect { case verifiedEmail if verifiedEmail.state == EmailAddressStates.VERIFIED =>
        processUpdate(RecordVerifiedEmail(verifiedEmail.userId, verifiedEmail.address))
      }

      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(sqsEmailAddressSeq, emails.map(_.seq).max)
      }
    }

    emails.length
  }

  def block(userId: Id[User], fullSocialId: FullSocialId): Unit = {
    val friendId = fullSocialId.identifier.left.map { socialId =>
      db.readOnly { implicit session =>
        socialUserInfoRepo.get.get(socialId, fullSocialId.network).id.get
      }
    }
    abook.blockRichConnection(userId, friendId)
  }
}

case class DefaultRepoModificationBatchingConfiguration[A <: RepoModificationActor[_]]() extends BatchingActorConfiguration[A] {
  val MaxBatchSize = 500
  val LowWatermarkBatchSize = 10
  val MaxBatchFlushInterval = 10 seconds
  val StaleEventAddTime = 40 seconds
  val StaleEventFlushTime = MaxBatchFlushInterval + StaleEventAddTime + (2 seconds)
}

trait RepoModificationEvent[M <: Model[M]] {
  val modif: RepoModification[M]
}

abstract class RepoModificationActor[R <: RepoModificationEvent[_]](airbrake: AirbrakeNotifier)(implicit tag: ClassTag[R]) extends BatchingActor[R](airbrake) {
  val batchingConf = DefaultRepoModificationBatchingConfiguration()
}

case class SocialConnectionModification(modif: RepoModification[SocialConnection]) extends RepoModificationEvent[SocialConnection]
class SocialConnectionModificationActor @Inject() (
  val clock: Clock,
  val scheduler: Scheduler,
  airbrake: AirbrakeNotifier,
  richConnectionCommander: ShoeboxRichConnectionCommander
) extends RepoModificationActor[SocialConnectionModification](airbrake) {
  def getEventTime(modification: SocialConnectionModification) = modification.modif.model.updatedAt
  def processBatch(modifications: Seq[SocialConnectionModification]) = if (richConnectionCommander.sendSocialConnections(batchingConf.MaxBatchSize) == batchingConf.MaxBatchSize) {
    flushPlease()
  }
}

case class UserConnectionModification(modif: RepoModification[UserConnection]) extends RepoModificationEvent[UserConnection]
class UserConnectionModificationActor @Inject() (
  val clock: Clock,
  val scheduler: Scheduler,
  airbrake: AirbrakeNotifier,
  richConnectionCommander: ShoeboxRichConnectionCommander
  ) extends RepoModificationActor[UserConnectionModification](airbrake) {
  def getEventTime(modification: UserConnectionModification) = modification.modif.model.updatedAt
  def processBatch(modifications: Seq[UserConnectionModification]) = if (richConnectionCommander.sendUserConnections(batchingConf.MaxBatchSize) == batchingConf.MaxBatchSize) {
    flushPlease()
  }
}

case class SocialUserInfoModification(modif: RepoModification[SocialUserInfo]) extends RepoModificationEvent[SocialUserInfo]
class SocialUserInfoModificationActor @Inject() (
  val clock: Clock,
  val scheduler: Scheduler,
  airbrake: AirbrakeNotifier,
  richConnectionCommander: ShoeboxRichConnectionCommander
  ) extends RepoModificationActor[SocialUserInfoModification](airbrake) {
  def getEventTime(modification: SocialUserInfoModification) = modification.modif.model.updatedAt
  def processBatch(modifications: Seq[SocialUserInfoModification]) = if (richConnectionCommander.sendSocialUsers(batchingConf.MaxBatchSize) == batchingConf.MaxBatchSize) {
    flushPlease()
  }
}

case class InvitationModification(modif: RepoModification[Invitation]) extends RepoModificationEvent[Invitation]
class InvitationModificationActor @Inject() (
  val clock: Clock,
  val scheduler: Scheduler,
  airbrake: AirbrakeNotifier,
  richConnectionCommander: ShoeboxRichConnectionCommander
  ) extends RepoModificationActor[InvitationModification](airbrake) {
  def getEventTime(modification: InvitationModification) = modification.modif.model.updatedAt
  def processBatch(modifications: Seq[InvitationModification]) = if (richConnectionCommander.sendInvitations(batchingConf.MaxBatchSize) == batchingConf.MaxBatchSize) {
    flushPlease()
  }
}

case class EmailAddressModification(modif: RepoModification[UserEmailAddress]) extends RepoModificationEvent[UserEmailAddress]
class EmailAddressModificationActor @Inject() (
  val clock: Clock,
  val scheduler: Scheduler,
  airbrake: AirbrakeNotifier,
  richConnectionCommander: ShoeboxRichConnectionCommander
  ) extends RepoModificationActor[EmailAddressModification](airbrake) {
  def getEventTime(modification: EmailAddressModification) = modification.modif.model.updatedAt
  def processBatch(modifications: Seq[EmailAddressModification]) = if (richConnectionCommander.sendEmailAddresses(batchingConf.MaxBatchSize) == batchingConf.MaxBatchSize) {
    flushPlease()
  }
}

