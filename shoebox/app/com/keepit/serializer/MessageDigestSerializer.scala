package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.social.CommentWithSocialUser
import com.keepit.common.db.State
import com.keepit.common.social.ThreadInfo

class ThreadInfoSerializer extends Writes[ThreadInfo] {

  def writes(ThreadInfo: ThreadInfo): JsValue =
    JsObject(List(
      "externalId" -> JsString(ThreadInfo.externalId.toString),
      "recipients" -> JsArray(ThreadInfo.recipients map (r => BasicUserSerializer.basicUserSerializer.writes(r))),
      "digest" -> JsString(ThreadInfo.digest),
      "messageCount" -> JsNumber(ThreadInfo.messageCount),
      "hasAttachments" -> JsBoolean(ThreadInfo.hasAttachments),
      "createdAt" -> JsString(ThreadInfo.createdAt.toString),
      "lastCommentedAt" -> JsString(ThreadInfo.lastCommentedAt.toString)
    ))

  def writes (comments: Seq[ThreadInfo]): JsValue =
    JsArray(comments map { comment =>
      ThreadInfoSerializer.ThreadInfoSerializer.writes(comment)
    })

  def writes(commentsGroups: List[(State[Comment.Permission],Seq[ThreadInfo])]): JsValue =
    JsObject(commentsGroups map { commentsGroup =>
      commentsGroup._1.value -> writes(commentsGroup._2)
    })
}

object ThreadInfoSerializer {
  implicit val ThreadInfoSerializer = new ThreadInfoSerializer
}
