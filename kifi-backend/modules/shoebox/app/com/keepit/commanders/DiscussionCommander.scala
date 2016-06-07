package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ Clock, CrossServiceTime, DEFAULT_DATE_TIME_ZONE }
import com.keepit.discussion.{ DiscussionFail, Message, MessageSource }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[DiscussionCommanderImpl])
trait DiscussionCommander {
  def markKeepsAsRead(userId: Id[User], lastSeenByKeep: Map[Id[Keep], Id[Message]]): Future[Map[Id[Keep], Int]]
  def sendMessageOnKeep(userId: Id[User], text: String, keepId: Id[Keep], source: Option[MessageSource]): Future[Message]
  def getMessagesOnKeep(userId: Id[User], keepId: Id[Keep], limit: Int, fromId: Option[Id[Message]]): Future[Seq[Message]]
  def editMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message], newText: String): Future[Message]
  def deleteMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message]): Future[Unit]
  def modifyConnectionsForKeep(userId: Id[User], keepId: Id[Keep], diff: KeepRecipientsDiff, source: Option[KeepEventSource]): Future[Unit]
}

@Singleton
class DiscussionCommanderImpl @Inject() (
  db: Database,
  clock: Clock,
  keepRepo: KeepRepo,
  ktlRepo: KeepToLibraryRepo,
  keepMutator: KeepMutator,
  eventCommander: KeepEventCommander,
  eliza: ElizaServiceClient,
  permissionCommander: PermissionCommander,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext)
    extends DiscussionCommander with Logging {
  implicit def csNow: CrossServiceTime = CrossServiceTime(clock.now)

  def markKeepsAsRead(userId: Id[User], lastSeenByKeep: Map[Id[Keep], Id[Message]]): Future[Map[Id[Keep], Int]] = {
    eliza.markKeepsAsReadForUser(userId, lastSeenByKeep)
  }
  def sendMessageOnKeep(userId: Id[User], text: String, keepId: Id[Keep], source: Option[MessageSource]): Future[Message] = {
    val failOpt = db.readOnlyReplica { implicit s =>
      val userCanSendMessage = permissionCommander.getKeepPermissions(keepId, Some(userId)).contains(KeepPermission.ADD_MESSAGE)
      if (!userCanSendMessage) Some(DiscussionFail.INSUFFICIENT_PERMISSIONS) else None
    }

    failOpt.map { fail =>
      airbrake.notify(s"[sendMessageOnKeep] $userId tried to sent message from ${source.map(_.value).getOrElse("unknown")} on $keepId without permission")
      Future.failed(fail)
    }.getOrElse {
      db.readWrite { implicit s =>
        keepMutator.unsafeModifyKeepRecipients(keepId, KeepRecipientsDiff.addUser(userId), userAttribution = Some(userId))
      }
      eliza.sendMessageOnKeep(userId, text, keepId, source)
    }
  }
  def getMessagesOnKeep(userId: Id[User], keepId: Id[Keep], limit: Int, fromIdOpt: Option[Id[Message]]): Future[Seq[Message]] = {
    val errs = db.readOnlyReplica { implicit s =>
      val userCanViewKeep = permissionCommander.getKeepPermissions(keepId, Some(userId)).contains(KeepPermission.VIEW_KEEP)
      Stream[Option[DiscussionFail]](
        Some(DiscussionFail.INSUFFICIENT_PERMISSIONS).filter(_ => !userCanViewKeep)
      ).flatten
    }

    errs.headOption.map(fail => Future.failed(fail)).getOrElse {
      eliza.getMessagesOnKeep(keepId, fromIdOpt, limit)
    }
  }
  def editMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message], newText: String): Future[Message] = {
    for {
      msg <- eliza.getCrossServiceMessages(Set(msgId)).map(_.values.headOption).flatMap { msgOpt =>
        msgOpt.filter(_.keep == keepId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.MESSAGE_DOES_NOT_EXIST_ON_KEEP))
      }
      owner <- msg.sentBy.filter(_.left.toOption.contains(userId)).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INSUFFICIENT_PERMISSIONS))
      editedMsg <- eliza.editMessage(msgId, newText)
    } yield editedMsg
  }
  def deleteMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message]): Future[Unit] = {
    val keepPermissions = db.readOnlyReplica { implicit s =>
      permissionCommander.getKeepPermissions(keepId, Some(userId))
    }
    def userCanDeleteMessagesFrom(who: Id[User]) = who match {
      case `userId` if keepPermissions.contains(KeepPermission.DELETE_OWN_MESSAGES) => true
      case _ if keepPermissions.contains(KeepPermission.DELETE_OTHER_MESSAGES) => true
      case _ => false
    }
    for {
      msg <- eliza.getCrossServiceMessages(Set(msgId)).map(_.values.headOption).flatMap { msgOpt =>
        msgOpt.filter(_.keep == keepId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.MESSAGE_DOES_NOT_EXIST_ON_KEEP))
      }
      owner <- msg.sentBy.filter(_.left.toOption.exists(userCanDeleteMessagesFrom)).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INSUFFICIENT_PERMISSIONS))
      res <- eliza.deleteMessage(msgId)
    } yield res
  }

  def modifyConnectionsForKeep(userId: Id[User], keepId: Id[Keep], diff: KeepRecipientsDiff, source: Option[KeepEventSource]): Future[Unit] = {
    if (diff.isEmpty) Future.successful(())
    else {
      val keepPermissions = db.readOnlyReplica(implicit s => permissionCommander.getKeepPermissions(keepId, Some(userId)))
      val errs: Stream[DiscussionFail] = Stream(
        (diff.users.added.nonEmpty && !keepPermissions.contains(KeepPermission.ADD_PARTICIPANTS)) -> DiscussionFail.INSUFFICIENT_PERMISSIONS,
        (diff.users.removed.exists(_ != userId) && !keepPermissions.contains(KeepPermission.REMOVE_PARTICIPANTS)) -> DiscussionFail.INSUFFICIENT_PERMISSIONS,
        (diff.libraries.added.nonEmpty && !keepPermissions.contains(KeepPermission.ADD_LIBRARIES)) -> DiscussionFail.INSUFFICIENT_PERMISSIONS,
        (diff.libraries.removed.nonEmpty && !keepPermissions.contains(KeepPermission.REMOVE_LIBRARIES)) -> DiscussionFail.INSUFFICIENT_PERMISSIONS
      ).collect { case (true, fail) => fail }

      errs.headOption.map(fail => Future.failed(fail)).getOrElse {
        db.readWrite { implicit s =>
          keepMutator.unsafeModifyKeepRecipients(keepId, diff, userAttribution = Some(userId)).tap { mutatedKeep =>
            if (!mutatedKeep.recipients.users.contains(userId) && !diff.users.removed.contains(userId)) {
              keepMutator.unsafeModifyKeepRecipients(keepId, KeepRecipientsDiff.addUser(userId), userAttribution = None)
            }
          }
          eventCommander.persistKeepEventAndUpdateEliza(keepId, KeepEventData.ModifyRecipients(userId, diff), source, eventTime = None)
        }
        Future.successful(Unit)
      }
    }
  }
}
