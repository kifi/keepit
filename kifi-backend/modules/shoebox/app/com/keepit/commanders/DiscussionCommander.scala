package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.discussion.Message
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

  def markKeepsAsRead(userId: Id[User], lastSeenByKeep: Map[Id[Keep], Id[Message]]): Future[Map[Id[Keep], Int]] = ???
  def sendMessageOnKeep(userId: Id[User], text: String, keepId: Id[Keep]): Future[Message] = ???
  def getMessagesOnKeep(userId: Id[User], keepId: Id[Keep], limit: Int, fromId: Option[Id[Message]]): Future[Seq[Message]] = ???
  def editMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message], newText: String): Future[Message] = ???
  def deleteMessageOnKeep(userId: Id[User], keepId: Id[Keep], msgId: Id[Message]): Future[Unit] = ???
}
