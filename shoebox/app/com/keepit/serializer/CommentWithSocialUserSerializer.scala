package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.social.CommentWithSocialUser
import com.keepit.common.db.State

class CommentWithSocialUserSerializer extends Writes[CommentWithSocialUser] {

  def writes(commentWithSocialUser: CommentWithSocialUser): JsValue =
    JsObject(List(
      "externalId" -> JsString(commentWithSocialUser.comment.externalId.toString),
      "createdAt" -> JsString(commentWithSocialUser.comment.createdAt.toString),
      "text" -> JsString(commentWithSocialUser.comment.text),
      "user" -> UserWithSocialSerializer.userWithSocialSerializer.writes(commentWithSocialUser.user),
      "permissions" -> JsString(commentWithSocialUser.comment.permissions.value),
      "replyCount" -> JsNumber(commentWithSocialUser.replyCount)
    ))

  def writes (comments: Seq[CommentWithSocialUser]): JsValue =
    JsArray(comments map { comment =>
      CommentWithSocialUserSerializer.commentWithSocialUserSerializer.writes(comment)
    })

  def writes(commentsGroups: List[(State[Comment.Permission],Seq[CommentWithSocialUser])]): JsValue =
    JsObject(commentsGroups map { commentsGroup =>
      commentsGroup._1.value -> writes(commentsGroup._2)
    })
}

object CommentWithSocialUserSerializer {
  implicit val commentWithSocialUserSerializer = new CommentWithSocialUserSerializer
}
