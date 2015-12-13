package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.json.{ TraversableFormat, KeyFormat }
import com.keepit.discussion.Message
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

@Singleton
class DiscussionController @Inject() (
  eliza: ElizaServiceClient,
  val userActionsHelper: UserActionsHelper,
  val db: Database,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val ec: ExecutionContext)
    extends UserActions with ShoeboxServiceController {

  def markKeepsAsRead() = UserAction.async(parse.tolerantJson) { request =>
    eliza.markKeepsAsReadForUser(request.userId, request.body.as[Set[Id[Keep]]]).map { res =>
      val ans = res.map {
        case (kid, unread) => Keep.publicId(kid) -> unread
      }
      val outputWrites = TraversableFormat.mapWrites[PublicId[Keep], Int](_.id)
      Ok(Json.toJson(ans)(outputWrites))
    }
  }
  def sendMessageOnKeep(keepId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    eliza.sendMessageOnKeep(request.userId, (request.body \ "text").as[String], Keep.decodePublicId(keepId).get).map { res =>
      Ok(Json.toJson(res))
    }
  }
  def getMessagesOnKeep(keepId: PublicId[Keep], limit: Int, fromId: Option[String]) = UserAction.async(parse.tolerantJson) { request =>
    eliza.getMessagesOnKeep(Keep.decodePublicId(keepId).get, fromId.map(s => Message.decodePublicId(PublicId(s)).get), limit).map { res =>
      Ok(Json.obj("messages" -> res))
    }
  }
  def editMessageOnKeep(keepId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    // TODO(ryan): validate request
    val inputReads = KeyFormat.key2Reads[PublicId[Message], String]("messageId", "newText")
    val (msgId, newText) = request.body.as[(PublicId[Message], String)](inputReads)
    eliza.editMessage(Message.decodePublicId(msgId).get, newText).map { res =>
      Ok(Json.toJson(res))
    }
  }
  def deleteMessageOnKeep(keepId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    // TODO(ryan): validate request
    val inputReads = KeyFormat.key1Reads[PublicId[Message]]("messageId")
    val msgId = request.body.as[PublicId[Message]](inputReads)
    eliza.deleteMessage(Message.decodePublicId(msgId).get).map { res =>
      Ok(Json.toJson(res))
    }
  }
}
