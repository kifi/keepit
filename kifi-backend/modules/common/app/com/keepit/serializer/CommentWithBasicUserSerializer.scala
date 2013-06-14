package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.common.social.CommentWithBasicUser
import com.keepit.common.db.State
import com.keepit.model._
import play.api.libs.json._

class CommentWithBasicUserSerializer extends Writes[CommentWithBasicUser] {
  def writes(commentWithBasicUser: CommentWithBasicUser): JsValue =
    Json.obj(
      "id" -> commentWithBasicUser.comment.externalId.id,
      "createdAt" -> commentWithBasicUser.comment.createdAt,
      "text" -> JsString(commentWithBasicUser.comment.text),
      "user" -> commentWithBasicUser.user,
      "permissions" -> commentWithBasicUser.comment.permissions.value,
      "recipients" -> commentWithBasicUser.recipients)

  def writes(comments: Seq[CommentWithBasicUser]): JsValue =
    JsArray(comments map writes)

  def writes(commentsGroups: List[(State[CommentPermission], Seq[CommentWithBasicUser])]): JsValue =
    JsObject(commentsGroups map { commentsGroup =>
      commentsGroup._1.value -> writes(commentsGroup._2)
    })

  def writes(commentsGroup: (State[CommentPermission], Seq[CommentWithBasicUser])): JsValue =
    writes(commentsGroup :: Nil)
}

object CommentWithBasicUserSerializer {
  implicit val commentWithBasicUserSerializer = new CommentWithBasicUserSerializer
}
