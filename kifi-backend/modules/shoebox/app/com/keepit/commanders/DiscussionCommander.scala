package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.discussion.{ DiscussionFail, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[DiscussionCommanderImpl])
trait DiscussionCommander {
  def markKeepsAsRead(userId: Id[User], lastSeenByKeep: Map[Id[Keep], Id[Message]]): Future[Map[Id[Keep], Int]]
  def sendMessageOnKeep(userId: Id[User], text: String, keepId: Id[Keep]): Future[Message]
  def getMessagesOnKeep(userId: Id[User], keepId: Id[Keep], limit: Int, fromId: Option[Id[Message]]): Future[Seq[Message]]
  def editMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message], newText: String): Future[Message]
  def deleteMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message]): Future[Unit]
}

@Singleton
class DiscussionCommanderImpl @Inject() (
  db: Database,
  eliza: ElizaServiceClient,
  permissionCommander: PermissionCommander,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext)
    extends DiscussionCommander with Logging {

  def markKeepsAsRead(userId: Id[User], lastSeenByKeep: Map[Id[Keep], Id[Message]]): Future[Map[Id[Keep], Int]] = {
    eliza.markKeepsAsReadForUser(userId, lastSeenByKeep)
  }
  def sendMessageOnKeep(userId: Id[User], text: String, keepId: Id[Keep]): Future[Message] = {
    val errs = db.readOnlyReplica { implicit s =>
      val userCanSendMessage = permissionCommander.getKeepPermissions(keepId, Some(userId)).contains(KeepPermission.ADD_MESSAGE)
      Stream[Option[DiscussionFail]](
        Some(DiscussionFail.INSUFFICIENT_PERMISSIONS).filter(_ => !userCanSendMessage)
      ).flatten
    }

    errs.headOption.map(fail => Future.failed(fail)).getOrElse {
      eliza.sendMessageOnKeep(userId, text, keepId)
    }
  }
  def getMessagesOnKeep(userId: Id[User], keepId: Id[Keep], limit: Int, fromIdOpt: Option[Id[Message]]): Future[Seq[Message]] = {
    val errs = db.readOnlyReplica { implicit s =>
      val userCanViewMessages = permissionCommander.getKeepPermissions(keepId, Some(userId)).contains(KeepPermission.VIEW_MESSAGES)
      Stream[Option[DiscussionFail]](
        Some(DiscussionFail.INSUFFICIENT_PERMISSIONS).filter(_ => !userCanViewMessages)
      ).flatten
    }

    errs.headOption.map(fail => Future.failed(fail)).getOrElse {
      eliza.getMessagesOnKeep(keepId, fromIdOpt, limit)
    }
  }
  def editMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message], newText: String): Future[Message] = {
    for {
      msg <- eliza.getCrossServiceMessages(Set(msgId)).map(_.values.head)
      owner <- msg.sentBy.filter(_ == userId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INSUFFICIENT_PERMISSIONS))
      editedMsg <- eliza.editMessage(msgId, newText)
    } yield editedMsg
  }
  def deleteMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message]): Future[Unit] = {
    for {
      msg <- eliza.getCrossServiceMessages(Set(msgId)).map(_.values.head)
      owner <- msg.sentBy.filter(_ == userId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INSUFFICIENT_PERMISSIONS))
      res <- eliza.deleteMessage(msgId)
    } yield res
  }
}
