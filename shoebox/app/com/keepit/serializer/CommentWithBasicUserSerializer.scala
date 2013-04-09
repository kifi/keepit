package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.social.CommentWithBasicUser
import com.keepit.common.db.State

class CommentWithBasicUserSerializer extends Writes[CommentWithBasicUser] {

  def writes(commentWithBasicUser: CommentWithBasicUser): JsValue =
    JsObject(List(
      "id" -> JsString(commentWithBasicUser.comment.externalId.toString),
      "externalId" -> JsString(commentWithBasicUser.comment.externalId.toString),  // TODO: remove after metro launch
      "createdAt" -> JsString(commentWithBasicUser.comment.createdAt.toString),
      "text" -> JsString(commentWithBasicUser.comment.text),
      "user" -> BasicUserSerializer.basicUserSerializer.writes(commentWithBasicUser.user),
      "permissions" -> JsString(commentWithBasicUser.comment.permissions.value),
      "replyCount" -> JsNumber(commentWithBasicUser.replyCount),
      "recipients" -> JsArray(commentWithBasicUser.recipients map (r => BasicUserSerializer.basicUserSerializer.writes(r)))
    ))

  def writes (comments: Seq[CommentWithBasicUser]): JsValue =
    JsArray(comments map { comment =>
      CommentWithBasicUserSerializer.commentWithBasicUserSerializer.writes(comment)
    })

  def writes(commentsGroups: List[(State[CommentPermission], Seq[CommentWithBasicUser])]): JsValue =
    JsObject(commentsGroups map { commentsGroup =>
      commentsGroup._1.value -> writes(commentsGroup._2)
    })


  def writes(commentsGroup: (State[CommentPermission], Seq[CommentWithBasicUser])): JsValue = writes(commentsGroup :: Nil)
}

object CommentWithBasicUserSerializer {
  implicit val commentWithBasicUserSerializer = new CommentWithBasicUserSerializer
}
