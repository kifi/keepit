package com.keepit.common.social

import com.keepit.model._
import play.api.libs.json._

case class CommentWithBasicUser(user: BasicUser, comment: Comment, recipients: Seq[BasicUser])

object CommentWithBasicUser {
  implicit val commentWithBasicUserFormat = Json.format[CommentWithBasicUser]
}