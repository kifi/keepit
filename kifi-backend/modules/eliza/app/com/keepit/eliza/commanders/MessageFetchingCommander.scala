package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.discussion.{ Message, DiscussionKeep }
import com.keepit.eliza.model._
import com.keepit.common.logging.Logging
import scala.concurrent.{ Promise, Future }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import scala.Some
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.{ Json, JsArray, JsString, JsNumber, JsBoolean, JsValue }
import com.keepit.model.{ Keep, Username, User }
import com.keepit.social.{ BasicUserLikeEntity, BasicUser, BasicNonUser }
import org.joda.time.DateTime
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
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  def getMessageWithBasicUser(
    id: PublicId[Message],
    createdAt: DateTime,
    text: String,
    source: Option[MessageSource],
    auxData: Option[JsArray],
    url: String,
    nUrl: String,
    user: Option[BasicUser],
    participants: Seq[BasicUserLikeEntity]): Future[MessageWithBasicUser] = {
    modifyMessageWithAuxData(MessageWithBasicUser(id, createdAt, text, source, auxData, url, nUrl, user.map(u => BasicUserLikeEntity(u)), participants))
  }

  //this is for internal use (not just this class, also several other commanders and tests). Do not use from a controller!
  def getThreadMessages(thread: MessageThread): Seq[ElizaMessage] = db.readOnlyMaster { implicit session =>
    log.info(s"[get_thread] trying to get thread messages for thread extId ${thread.externalId}")
    messageRepo.get(thread.id.get, 0)
  }

  def getThreadMessagesWithBasicUser(thread: MessageThread): Future[Seq[MessageWithBasicUser]] = {
    val userParticipantSet = thread.participants.allUsers
    log.info(s"[get_thread] got participants for extId ${thread.externalId}: $userParticipantSet")
    val messagesFut: Future[Seq[MessageWithBasicUser]] = new SafeFuture(shoebox.getBasicUsers(userParticipantSet.toSeq) map { id2BasicUser =>
      val messages = getThreadMessages(thread)
      log.info(s"[get_thread] got raw messages for extId ${thread.externalId}: ${messages.length}")
      messages.map { message =>
        val nonUsers = thread.participants.allNonUsers.map(NonUserParticipant.toBasicNonUser)
        MessageWithBasicUser(
          id = message.pubId,
          createdAt = message.createdAt,
          text = message.messageText,
          source = message.source,
          auxData = message.auxData,
          url = message.sentOnUrl.getOrElse(""),
          nUrl = thread.nUrl,
          user = message.from match {
            case MessageSender.User(id) => Some(BasicUserLikeEntity(id2BasicUser(id)))
            case MessageSender.NonUser(nup) => Some(BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup)))
            case _ => None
          },
          participants = userParticipantSet.toSeq.map(user => BasicUserLikeEntity(id2BasicUser(user))) ++
            nonUsers.map(nonUser => BasicUserLikeEntity(nonUser))
        )
      }
    })
    messagesFut flatMap { messages =>
      Future.sequence(messages map { message => modifyMessageWithAuxData(message) })
    }
  }

  def getDiscussionAndKeep(userId: Id[User], threadExtId: MessageThreadId): Future[(BasicDiscussion, Option[DiscussionKeep])] = {
    db.readOnlyMaster(threadRepo.getByMessageThreadId(threadExtId)(_)) match {
      case Some(thread) =>
        val futureMessages = getThreadMessagesWithBasicUser(thread)
        val futureKeep = thread.keepId.map(keepId => shoebox.getDiscussionKeepsByIds(userId, Set(keepId)).imap(_.get(keepId))) getOrElse Future.successful(None)
        for {
          messages <- futureMessages
          discussionKeepOpt <- futureKeep
        } yield {
          (BasicDiscussion(thread.url, thread.nUrl, messages.flatMap(_.participants).toSet, messages), discussionKeepOpt)
        }
      case None =>
        val KeepId(keepId) = threadExtId
        val futureDiscussionKeep = shoebox.getDiscussionKeepsByIds(userId, Set(keepId)).imap(_.apply(keepId))
        val futureNormalizedUrl = for {
          keep <- shoebox.getCrossServiceKeepsByIds(Set(keepId)).imap(_.apply(keepId))
          uri <- shoebox.getNormalizedURI(keep.uriId)
        } yield uri.url

        for {
          discussionKeep <- futureDiscussionKeep
          nUrl <- futureNormalizedUrl
        } yield {
          (BasicDiscussion(discussionKeep.url, nUrl, Set(BasicUserLikeEntity(discussionKeep.keptBy)), Seq.empty), Some(discussionKeep))
        }
    }
  }

  def processParticipantsMessage(jsAdderUserId: String, jsAddedUsers: Seq[JsValue], isInitialMessage: Boolean = false): Future[(String, JsArray)] = {
    val (addedUsersJson, addedNonUsersJson) = jsAddedUsers.partition(_.isInstanceOf[JsNumber])
    val addedUsers = addedUsersJson.map(id => Id[User](id.as[Long]))
    val addedNonUsers = addedNonUsersJson.map(_.as[NonUserParticipant])
    val adderUserId = Id[User](jsAdderUserId.toLong)
    new SafeFuture(shoebox.getBasicUsers(adderUserId +: addedUsers) map { basicUsers =>
      val adderUser = basicUsers(adderUserId)
      val addedBasicUsers = addedUsers.map(u => BasicUserLikeEntity(basicUsers(u))) ++
        addedNonUsers.map(participant => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(participant)))
      val addedUsersString = addedBasicUsers.map { bule =>
        bule match {
          case BasicUserLikeEntity.user(bu) => s"${bu.firstName} ${bu.lastName}"
          case BasicUserLikeEntity.nonUser(bnu) => bnu.lastName.map(ln => s"${bnu.firstName.get} $ln").getOrElse(bnu.firstName.get)
          case _ => "Kifi User"
        }
      }.toList match {
        case first :: Nil => first
        case first :: second :: Nil => first + " and " + second
        case many => many.take(many.length - 1).mkString(", ") + ", and " + many.last
      }
      if (isInitialMessage) {
        val friendlyMessage = s"${adderUser.firstName} ${adderUser.lastName} started a discussion with $addedUsersString on this page."
        (friendlyMessage, Json.arr("start_with_emails", basicUsers(adderUserId), addedBasicUsers))
      } else {
        val friendlyMessage = s"${adderUser.firstName} ${adderUser.lastName} added $addedUsersString to the discussion."
        (friendlyMessage, Json.arr("add_participants", basicUsers(adderUserId), addedBasicUsers))
      }
    })
  }

  // todo(stephen): This should be the only way to make a MessageWithBasicUser, signature shouldn't take one in
  private def modifyMessageWithAuxData(m: MessageWithBasicUser): Future[MessageWithBasicUser] = {
    if (m.user.isEmpty) {
      val modifiedMessage = m.auxData match {
        case Some(auxData) =>
          val auxModifiedFuture = auxData.value match {
            case JsString("add_participants") +: JsString(jsAdderUserId) +: JsArray(jsAddedUsers) +: _ => {
              processParticipantsMessage(jsAdderUserId, jsAddedUsers)
            }
            case JsString("start_with_emails") +: JsString(jsAdderUserId) +: JsArray(jsAddedUsers) +: _ => {
              processParticipantsMessage(jsAdderUserId, jsAddedUsers, true)
            }
            case s => {
              Promise.successful(("", Json.arr())).future
            }
          }
          auxModifiedFuture.map {
            case (text, aux) =>
              m.copy(auxData = Some(aux), text = text, user = Some(BasicUserLikeEntity(BasicUser(ExternalId[User]("42424242-4242-4242-4242-000000000001"), "Kifi", "", "0.jpg", Username("sssss")))))
          }
        case None =>
          Promise.successful(m).future
      }
      modifiedMessage
    } else {
      Promise.successful(m).future
    }
  }

  def getMessagePublicId(messageIdStr: String): PublicId[Message] = ExternalId.asOpt[ElizaMessage](messageIdStr) match {
    case Some(externalId) => db.readOnlyMaster { implicit session => messageRepo.get(externalId).pubId }
    case None => Message.validatePublicId(messageIdStr).get
  }

  def getElizaMessageId(messageIdStr: String): Id[ElizaMessage] = ExternalId.asOpt[ElizaMessage](messageIdStr) match {
    case Some(externalId) => db.readOnlyMaster { implicit session => messageRepo.get(externalId).id.get }
    case None => ElizaMessage.fromCommon(Message.validatePublicId(messageIdStr).flatMap(Message.decodePublicId(_).toOption).get)
  }
}
