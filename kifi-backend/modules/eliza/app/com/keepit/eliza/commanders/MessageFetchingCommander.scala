package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.util.DescriptionElements
import com.keepit.discussion.{ MessageSource, DiscussionFail, DiscussionKeep }
import com.keepit.eliza.model._
import com.keepit.common.logging.Logging
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import com.keepit.model.KeepEventData.{ ModifyRecipients, EditTitle }
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.model._
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.core._

// todo(Léo): revisit this with full keepscussions
case class BasicDiscussion(
  url: String,
  nUrl: String,
  participants: Set[BasicUserLikeEntity],
  messages: Seq[MessageWithBasicUser])

class MessageFetchingCommander @Inject() (
    db: Database,
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    shoebox: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  def getMessageWithBasicUser(msg: ElizaMessage, thread: MessageThread, basicUserById: Map[Id[User], BasicUser]): MessageWithBasicUser = {
    val participants = {
      val basicUsers = thread.participants.allUsers.toSeq.map(u => BasicUserLikeEntity(basicUserById(u)))
      val basicEmails = thread.participants.emailParticipants.keySet.map(nup => BasicUserLikeEntity(EmailParticipant.toBasicNonUser(nup)))
      basicUsers ++ basicEmails
    }

    val from = msg.from match {
      case MessageSender.User(id) => BasicUserLikeEntity(basicUserById(id))
      case MessageSender.NonUser(nup) => BasicUserLikeEntity(EmailParticipant.toBasicNonUser(nup))
      case MessageSender.System => BasicUserLikeEntity(BasicUser(ExternalId[User]("42424242-4242-4242-4242-000000000001"), "Kifi", "", "0.jpg", Username("sssss")))
    }

    val auxData = msg.auxData.map(SystemMessageData.publish(_, basicUserById))

    MessageWithBasicUser(
      BasicKeepEventId.fromMsg(ElizaMessage.toCommonId(msg.id.get)),
      msg.createdAt,
      msg.messageText,
      msg.source,
      auxData,
      msg.sentOnUrl.getOrElse(thread.url),
      thread.nUrl,
      from,
      participants
    )
  }

  //this is for internal use (not just this class, also several other commanders and tests). Do not use from a controller!
  def getMessagesByKeepId(keepId: Id[Keep]): Seq[ElizaMessage] = db.readOnlyMaster { implicit session =>
    log.info(s"[get_thread] trying to get thread messages for keepId $keepId")
    messageRepo.get(keepId, 0)
  }

  def getThreadMessagesWithBasicUser(thread: MessageThread): Future[Seq[MessageWithBasicUser]] = {
    val userParticipantSet = thread.participants.allUsers
    log.info(s"[get_thread] got participants for keepId ${thread.keepId}: $userParticipantSet")
    val messagesFut: Future[Seq[MessageWithBasicUser]] = new SafeFuture(shoebox.getBasicUsers(userParticipantSet.toSeq) map { id2BasicUser =>
      val messages = getMessagesByKeepId(thread.keepId).filter(_.auxData.forall(SystemMessageData.isFullySupported))
      log.info(s"[get_thread] got raw messages for keepId ${thread.keepId}: ${messages.length}")
      messages.map(getMessageWithBasicUser(_, thread, id2BasicUser))
    })
    messagesFut
  }

  def getDiscussionAndKeep(userId: Id[User], keepId: Id[Keep]): Future[(BasicDiscussion, DiscussionKeep)] = {
    db.readOnlyMaster(threadRepo.getByKeepId(keepId)(_)) match {
      case Some(thread) =>
        val futureMessages = getThreadMessagesWithBasicUser(thread)
        val futureKeep = shoebox.getDiscussionKeepsByIds(userId, Set(thread.keepId)).imap(_.get(thread.keepId))
        for {
          messages <- futureMessages
          discussionKeepOpt <- futureKeep
          discussionKeep <- discussionKeepOpt.map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
          _ <- Some(()).filter(_ => discussionKeep.permissions.contains(KeepPermission.ADD_MESSAGE)).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INSUFFICIENT_PERMISSIONS))
        } yield (BasicDiscussion(thread.url, thread.nUrl, messages.flatMap(_.participants).toSet, messages), discussionKeep)
      case None =>
        val futureDiscussionKeep = shoebox.getDiscussionKeepsByIds(userId, Set(keepId)).imap(_.get(keepId))
        val futureNormalizedUrl = for {
          keep <- shoebox.getCrossServiceKeepsByIds(Set(keepId)).imap(_.apply(keepId))
          uri <- shoebox.getNormalizedURI(keep.uriId)
        } yield uri.url

        for {
          discussionKeepOpt <- futureDiscussionKeep
          discussionKeep <- discussionKeepOpt.map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
          _ <- Some(()).filter(_ => discussionKeep.permissions.contains(KeepPermission.ADD_MESSAGE)).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INSUFFICIENT_PERMISSIONS))
          nUrl <- futureNormalizedUrl
        } yield (BasicDiscussion(discussionKeep.url, nUrl, discussionKeep.keptBy.map(BasicUserLikeEntity(_)).toSet, Seq.empty), discussionKeep)
    }
  }
}
