package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.social.SocialNetworkType
import play.api.libs.json.Json

case class SocialConnection(
    id: Option[Id[SocialConnection]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    socialUser1: Id[SocialUserInfo],
    socialUser2: Id[SocialUserInfo],
    state: State[SocialConnection] = SocialConnectionStates.ACTIVE,
    seq: SequenceNumber[SocialConnection] = SequenceNumber.ZERO) extends ModelWithState[SocialConnection] with ModelWithSeqNumber[SocialConnection] {
  def withId(id: Id[SocialConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[SocialConnection]) = copy(state = state)
}

object SocialConnectionStates extends States[SocialConnection]

case class IndexableSocialConnection(
  firstSocialUserId: Id[SocialUserInfo],
  secondSocialUserId: Id[SocialUserInfo],
  network: SocialNetworkType,
  state: State[SocialConnection],
  seq: SequenceNumber[SocialConnection])

object IndexableSocialConnection {
  implicit val format = Json.format[IndexableSocialConnection]
}
