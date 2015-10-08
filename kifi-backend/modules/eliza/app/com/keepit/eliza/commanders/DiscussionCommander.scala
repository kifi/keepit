package com.keepit.eliza.commanders

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.{MessageThread, MessageThreadParticipants, MessageThreadRepo}
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.shoebox.ShoeboxDiscussionServiceClient

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[DiscussionCommanderImpl])
trait DiscussionCommander {
  def startDiscussionThread(rawDiscussion: RawDiscussion): Future[Discussion]
  def sendMessageOnDiscussion(senderId: Id[User], keepId: KeepId, messageText: String): Unit
}

@Singleton
class DiscussionCommanderImpl @Inject() (
    db: Database,
    discussionClient: ShoeboxDiscussionServiceClient,
    messageThreadRepo: MessageThreadRepo,
    messagingCommander: MessagingCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends DiscussionCommander with Logging {

  def startDiscussionThread(rawDiscussion: RawDiscussion): Future[Discussion] = {
    discussionClient.createKeep(rawDiscussion).flatMap { discussion =>
      val existingThreadOpt = db.readOnlyReplica { implicit session => messageThreadRepo.getByKeepId(discussion.keepId) }
      existingThreadOpt match {
        case Some(existingThread) => Future.successful(discussion)
        case None =>
          val mtps = MessageThreadParticipants(discussion.users)
          val thread = db.readWrite { implicit session =>
            messageThreadRepo.save(MessageThread(
              keepId = Some(discussion.keepId),
              uriId = Some(discussion.uriId),
              url = Some(discussion.url),
              nUrl = Some(discussion.url),
              pageTitle = None,
              participants = Some(mtps),
              participantsHash = Some(mtps.userHash),
              replyable = true,
              asyncStatus = MessageThreadAsyncStatus.WAITING_FOR_KEEP
            ))
          }
          discussionClient.linkKeepToDiscussion(discussion.keepId, thread.id.get).map { kid =>
            require(kid == discussion.keepId)
            val finalizedThread = db.readWrite { implicit session =>
              messageThreadRepo.save(thread.withAsyncStatus(MessageThreadAsyncStatus.OKAY))
            }
            discussion
          }
      }
    }
  }

  def sendMessageOnDiscussion(senderId: Id[User], keepId: KeepId, messageText: String): Unit = {
    val mt = db.readOnlyMaster { implicit session =>
      messageThreadRepo.getByKeepId(keepId).get
    }
    implicit val context = HeimdalContext.empty
    messagingCommander.sendMessage(senderId, mt.id.get, messageText, None, None)
  }
}
