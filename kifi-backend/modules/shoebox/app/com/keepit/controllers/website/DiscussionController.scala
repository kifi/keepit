package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.DiscussionCommander
import com.keepit.common.controller.{ UserRequest, ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.json.{ TraversableFormat, KeyFormat }
import com.keepit.discussion.{ MessageSource, DiscussionFail, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilderFactory }
import com.keepit.model._
import play.api.libs.json.{ JsValue, Json }

import scala.concurrent.{ Future, ExecutionContext }

@Singleton
class DiscussionController @Inject() (
  eliza: ElizaServiceClient,
  discussionCommander: DiscussionCommander,
  val userActionsHelper: UserActionsHelper,
  val db: Database,
  userRepo: UserRepo,
  protected val heimdalContextBuilder: HeimdalContextBuilderFactory,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val ec: ExecutionContext)
    extends UserActions with ShoeboxServiceController {

  def markKeepsAsRead() = UserAction.async(parse.tolerantJson) { request =>
    implicit val inputFormat = KeyFormat.key2Reads[PublicId[Keep], PublicId[Message]]("keepId", "lastMessage")
    (for {
      parsedInput <- request.body.validate[Seq[(PublicId[Keep], PublicId[Message])]].map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      input = parsedInput.map {
        case (keepPubId, msgPubId) => Keep.decodePublicId(keepPubId).get -> Message.decodePublicId(msgPubId).get
      }.toMap
      unreadCountsByKeep <- discussionCommander.markKeepsAsRead(request.userId, input)
    } yield {
      implicit val outputWrites = TraversableFormat.mapWrites[PublicId[Keep], Int](_.id)
      val res = unreadCountsByKeep.map {
        case (kid, unreadCount) => Keep.publicId(kid) -> unreadCount
      }
      Ok(Json.obj("unreadCounts" -> res))
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
  def sendMessageOnKeep(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    (for {
      text <- (request.body \ "text").asOpt[String].map(Future.successful).getOrElse(Future.failed(DiscussionFail.MISSING_MESSAGE_TEXT))
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      msg <- discussionCommander.sendMessageOnKeep(request.userId, text, keepId, Some(MessageSource.SITE))
    } yield {
      Ok(Json.toJson(msg))
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
  def getMessagesOnKeep(pubId: PublicId[Keep], limit: Int, fromPubIdOpt: Option[String]) = UserAction.async { request =>
    val fromIdOptFut = fromPubIdOpt.filter(_.nonEmpty) match {
      case None => Future.successful(None)
      case Some(fromPubId) =>
        Message.decodePublicId(PublicId(fromPubId)).map(x => Future.successful(Some(x))).getOrElse(Future.failed(DiscussionFail.INVALID_MESSAGE_ID))
    }
    (for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      fromIdOpt <- fromIdOptFut
      msgs <- discussionCommander.getMessagesOnKeep(request.userId, keepId, limit, fromIdOpt)
    } yield {
      Ok(Json.obj("messages" -> msgs))
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
  def editMessageOnKeep(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    val inputReads = KeyFormat.key2Reads[PublicId[Message], String]("messageId", "newText")
    (for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      (msgPubId, newText) <- request.body.validate[(PublicId[Message], String)](inputReads).map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      msgId <- Message.decodePublicId(msgPubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_MESSAGE_ID))
      editedMsg <- discussionCommander.editMessageOnKeep(request.userId, keepId, msgId, newText)
    } yield {
      Ok(Json.obj("message" -> editedMsg))
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
  def deleteMessageOnKeep(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    val inputReads = KeyFormat.key1Reads[PublicId[Message]]("messageId")
    (for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      msgPubId <- request.body.validate[PublicId[Message]](inputReads).map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      msgId <- Message.decodePublicId(msgPubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_MESSAGE_ID))
      _ <- discussionCommander.deleteMessageOnKeep(request.userId, keepId, msgId)
    } yield {
      NoContent
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }

  def editParticipantsOnKeep(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    import com.keepit.common.http._
    val source = request.userAgentOpt.flatMap(KeepEventSourceKind.fromUserAgent)
    (for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      extUsersToAdd <- (request.body \ "add").validate[Set[ExternalId[User]]].map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      idMap <- db.readOnlyReplicaAsync { implicit s => userRepo.convertExternalIds(extUsersToAdd) }
      usersToAdd = extUsersToAdd.flatMap(idMap.get)
      _ <- discussionCommander.editParticipantsOnKeep(request.userId, keepId, usersToAdd, source)
    } yield {
      NoContent
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
}
