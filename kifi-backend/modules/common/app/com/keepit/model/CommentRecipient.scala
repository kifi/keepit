package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class CommentRecipient(
  id: Option[Id[CommentRecipient]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  commentId: Id[Comment],
  userId: Option[Id[User]] = None,
  socialUserId: Option[Id[SocialUserInfo]] = None,
  email: Option[String] = None, // change me?
  state: State[CommentRecipient] = CommentRecipientStates.ACTIVE
) extends Model[CommentRecipient] {
  require(userId.isDefined || socialUserId.isDefined || email.isDefined)
  def withId(id: Id[CommentRecipient]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def withState(state: State[CommentRecipient]) = copy(state = state)
  def withUser(userId: Id[User]) = copy(userId = Some(userId), socialUserId = None, email = None)
  def withSocialUserInfo(socialUserId: Id[SocialUserInfo]) = copy(socialUserId = Some(socialUserId), userId = None, email = None)
  def withEmail(email: String) = copy(email = Some(email), userId = None, socialUserId = None)
  def get = userId.getOrElse(socialUserId.getOrElse(email.getOrElse(throw new Exception("No recipient specified!"))))
}

object CommentRecipientStates extends States[CommentRecipient]
