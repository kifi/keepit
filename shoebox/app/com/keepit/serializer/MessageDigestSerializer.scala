package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.social.CommentWithSocialUser
import com.keepit.common.db.State
import com.keepit.common.social.MessageDigest

class MessageDigestSerializer extends Writes[MessageDigest] {

  def writes(messageDigest: MessageDigest): JsValue =
    JsObject(List(
      "externalId" -> JsString(messageDigest.externalId.toString),
      "recipients" -> JsArray(messageDigest.recipients map (r => BasicUserSerializer.basicUserSerializer.writes(r))),
      "digest" -> JsString(messageDigest.digest),
      "messageCount" -> JsNumber(messageDigest.messageCount),
      "hasAttachments" -> JsBoolean(messageDigest.hasAttachments),
      "createdAt" -> JsString(messageDigest.createdAt.toString),
      "lastCommentedAt" -> JsString(messageDigest.lastCommentedAt.toString)
    ))

  def writes (comments: Seq[MessageDigest]): JsValue =
    JsArray(comments map { comment =>
      MessageDigestSerializer.messageDigestSerializer.writes(comment)
    })

  def writes(commentsGroups: List[(State[Comment.Permission],Seq[MessageDigest])]): JsValue =
    JsObject(commentsGroups map { commentsGroup =>
      commentsGroup._1.value -> writes(commentsGroup._2)
    })
}

object MessageDigestSerializer {
  implicit val messageDigestSerializer = new MessageDigestSerializer
}
