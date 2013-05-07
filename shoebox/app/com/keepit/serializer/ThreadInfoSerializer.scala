package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.social.CommentWithBasicUser
import com.keepit.common.db.State
import com.keepit.common.social.ThreadInfo

class ThreadInfoSerializer extends Writes[ThreadInfo] {
  def writes(o: ThreadInfo): JsValue =
    Json.obj(
      "id" -> o.externalId.id,
      "recipients" -> o.recipients,
      "digest" -> o.digest,
      "messageCount" -> o.messageCount,
      "messageTimes" -> o.messageTimes.map(m => m._1.id -> m._2),
      "createdAt" -> o.createdAt,
      "lastCommentedAt" -> o.lastCommentedAt)

  def writes (comments: Seq[ThreadInfo]): JsValue = JsArray(comments map writes)

  def writes(commentsGroups: List[(State[CommentPermission], Seq[ThreadInfo])]): JsValue =
    JsObject(commentsGroups map { commentsGroup =>
      commentsGroup._1.value -> writes(commentsGroup._2)
    })

  def writes(commentsGroup: (State[CommentPermission], Seq[ThreadInfo])): JsValue = writes(commentsGroup :: Nil)
}

object ThreadInfoSerializer {
  implicit val threadInfoSerializer = new ThreadInfoSerializer
}
