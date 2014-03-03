package com.keepit.commanders

import com.keepit.abook.ABookServiceClient
import com.keepit.common.queue.{
  RichConnectionUpdateMessage,
  CreateRichConnection,
  RecordKifiConnection,
  RecordInvitation,
  RecordFriendUserId
}
import com.keepit.common.db.slick.{
  RepoModification,
  RepoEntryAdded,
  RepoEntryUpdated
}
import com.keepit.model.{
  SocialConnection,
  UserConnection,
  Invitation,
  SocialUserInfo,
  SocialUserInfoRepo
}
import com.keepit.common.db.slick.Database

import com.kifi.franz.FormattedSQSQueue

import com.google.inject.{Inject, Singleton, Provider}
import com.keepit.common.actor.{BatchingActor, BatchingActorConfiguration}
import scala.concurrent.duration._
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.reflect.ClassTag
import com.keepit.common.db.Model
import akka.actor.Scheduler
import com.keepit.common.time.Clock

@Singleton
class ShoeboxRichConnectionCommander @Inject() (
    abook: ABookServiceClient,
    queue: FormattedSQSQueue[RichConnectionUpdateMessage],
    socialUserInfoRepo: Provider[SocialUserInfoRepo],
    db: Database
  ) extends RemoteRichConnectionCommander(abook, queue) {

  def sendSocialConnections(): Unit = { // @todo(Léo) to be implemented
  }
  def sendUserConnections(): Unit= { // @todo(Léo) to be implemented
  }
  def sendSocialUsers(): Unit = { // @todo(Léo) to be implemented
  }
  def sendInvitations(): Unit = { // @todo(Léo) to be implemented
  }

  def processSocialConnectionChange(change: RepoModification[SocialConnection]): Unit = {
    change match {
      case RepoEntryAdded(socialConnection) => {
        val (sUser1, sUser2) = db.readOnly { implicit session =>
          (socialUserInfoRepo.get.get(socialConnection.socialUser1), socialUserInfoRepo.get.get(socialConnection.socialUser2))
        }
        sUser1.userId.map { userId =>
          processUpdate(CreateRichConnection(userId, sUser1.id.get, sUser2))
        }
        sUser2.userId.map { userId =>
          processUpdate(CreateRichConnection(userId, sUser2.id.get, sUser1))
        }
      }
      case _ =>
    }
  }

  def processUserConnectionChange(change: RepoModification[UserConnection]): Unit = {
    change match {
      case RepoEntryAdded(userConnection) => {
        processUpdate(RecordKifiConnection(userConnection.user1, userConnection.user2))
      }
      case _ =>
    }
  }

  def processInvitationChange(change: RepoModification[Invitation]): Unit = { //ZZZ incomplete. Need to deal with networkType (unused on the other end right now) and email invites
    change match {
      case RepoEntryAdded(invitation) => {
        invitation.senderUserId.map { userId =>
          processUpdateImmediate(RecordInvitation(userId, invitation.id.get, "thisisthenetworktype", invitation.recipientSocialUserId, None))
        }
      }
      case _ =>
    }
  }

  def processSocialUserChange(change: RepoModification[SocialUserInfo]): Unit = { //ZZZ incomplete. Need to deal with networkType (unused on the other end right now) and email
    change match {
      case RepoEntryAdded(socialUserInfo) => {
        socialUserInfo.userId.map{ userId =>
          processUpdate(RecordFriendUserId("thisisthenetworktype", socialUserInfo.id, None, userId))
        }
      }
      case RepoEntryUpdated(socialUserInfo) => {
        socialUserInfo.userId.map{ userId =>
          processUpdate(RecordFriendUserId("thisisthenetworktype", socialUserInfo.id, None, userId))
        }
      }
      case _ =>
    }
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
  def processBatch(modifications: Seq[SocialConnectionModification]) = richConnectionCommander.sendSocialConnections()
}

case class UserConnectionModification(modif: RepoModification[UserConnection]) extends RepoModificationEvent[UserConnection]
class UserConnectionModificationActor @Inject() (
  val clock: Clock,
  val scheduler: Scheduler,
  airbrake: AirbrakeNotifier,
  richConnectionCommander: ShoeboxRichConnectionCommander
  ) extends RepoModificationActor[UserConnectionModification](airbrake) {
  def getEventTime(modification: UserConnectionModification) = modification.modif.model.updatedAt
  def processBatch(modifications: Seq[UserConnectionModification]) = richConnectionCommander.sendUserConnections()
}

case class SocialUserInfoModification(modif: RepoModification[SocialUserInfo]) extends RepoModificationEvent[SocialUserInfo]
class SocialUserInfoModificationActor @Inject() (
  val clock: Clock,
  val scheduler: Scheduler,
  airbrake: AirbrakeNotifier,
  richConnectionCommander: ShoeboxRichConnectionCommander
  ) extends RepoModificationActor[SocialUserInfoModification](airbrake) {
  def getEventTime(modification: SocialUserInfoModification) = modification.modif.model.updatedAt
  def processBatch(modifications: Seq[SocialUserInfoModification]) = richConnectionCommander.sendSocialUsers()
}

case class InvitationModification(modif: RepoModification[Invitation]) extends RepoModificationEvent[Invitation]
class InvitationModificationActor @Inject() (
  val clock: Clock,
  val scheduler: Scheduler,
  airbrake: AirbrakeNotifier,
  richConnectionCommander: ShoeboxRichConnectionCommander
  ) extends RepoModificationActor[InvitationModification](airbrake) {
  def getEventTime(modification: InvitationModification) = modification.modif.model.updatedAt
  def processBatch(modifications: Seq[InvitationModification]) = richConnectionCommander.sendInvitations()
}

