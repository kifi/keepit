package com.keepit.abook.model


import com.keepit.common.db.{ModelWithState, Id, State, States}
import com.keepit.model.{User, SocialUserInfo, Invitation}

import org.joda.time.DateTime
import com.keepit.common.time._

object RichSocialConnectionStates extends States[RichSocialConnection]

case class RichSocialConnection(
  id: Option[Id[RichSocialConnection]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[RichSocialConnection] = RichSocialConnectionStates.ACTIVE,

  userId: Id[User],
  userSocialId: Option[Id[SocialUserInfo]],
  connectionType: String, //needs typing
  friendSocialId: Option[Id[SocialUserInfo]],
  friendEmailAddress: Option[String],
  friendUserId: Option[Id[User]],
  localFriendCount: Int,
  globalFriendCount: Int,
  invitation: Option[Id[Invitation]],
  blocked: Boolean
) extends ModelWithState[RichSocialConnection] {

  def withId(id: Id[RichSocialConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}
