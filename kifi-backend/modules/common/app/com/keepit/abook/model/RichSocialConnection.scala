package com.keepit.abook.model

import com.keepit.common.db._
import com.keepit.model._
import com.keepit.social.{ SocialId, SocialNetworkType }

import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.json.EitherFormat

object RichSocialConnectionStates extends States[RichSocialConnection]

case class RichSocialConnection(
    id: Option[Id[RichSocialConnection]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[RichSocialConnection] = RichSocialConnectionStates.ACTIVE,

    userId: Id[User],
    userSocialId: Option[Id[SocialUserInfo]],
    connectionType: SocialNetworkType,
    friendSocialId: Option[Id[SocialUserInfo]],
    friendEmailAddress: Option[EmailAddress],
    friendName: Option[String],
    friendUserId: Option[Id[User]],
    commonKifiFriendsCount: Int,
    kifiFriendsCount: Int,
    invitationsSent: Int,
    invitedBy: Int,
    blocked: Boolean) extends ModelWithState[RichSocialConnection] {

  def withId(id: Id[RichSocialConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object RichSocialConnection {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[RichSocialConnection]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[RichSocialConnection]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'userSocialId).formatNullable(Id.format[SocialUserInfo]) and
    (__ \ 'connectionType).format[SocialNetworkType] and
    (__ \ 'friendSocialId).formatNullable(Id.format[SocialUserInfo]) and
    (__ \ 'friendEmailAddress).formatNullable[EmailAddress] and
    (__ \ 'friendName).formatNullable[String] and
    (__ \ 'friendUserId).formatNullable((Id.format[User])) and
    (__ \ 'commonKifiFriendsCount).format[Int] and
    (__ \ 'kifiFriendsCount).format[Int] and
    (__ \ 'invitationsSent).format[Int] and
    (__ \ 'invitedBy).format[Int] and
    (__ \ 'blocked).format[Boolean]
  )(RichSocialConnection.apply, unlift(RichSocialConnection.unapply))
}

case class UserInviteRecommendation(
  network: SocialNetworkType,
  identifier: Either[EmailAddress, SocialId],
  name: Option[String],
  pictureUrl: Option[String],
  lastInvitedAt: Option[DateTime],
  score: Double)

object UserInviteRecommendation {
  val identifierFormat = EitherFormat[EmailAddress, SocialId]
  implicit val format = (
    (__ \ 'network).format[SocialNetworkType] and
    (__ \ 'identifier).format(identifierFormat) and
    (__ \ 'name).formatNullable[String] and
    (__ \ 'pictureUrl).formatNullable[String] and
    (__ \ 'lastInvitedAt).formatNullable(DateTimeJsonFormat) and
    (__ \ 'score).format[Double]
  )(UserInviteRecommendation.apply, unlift(UserInviteRecommendation.unapply))
}

case class OrganizationInviteRecommendation(
  emailAddress: EmailAddress,
  name: Option[String],
  firstInvitedAt: Option[DateTime],
  score: Double)

object OrganizationInviteRecommendation {
  implicit val format = (
    (__ \ 'emailAddress).format[EmailAddress] and
    (__ \ 'name).formatNullable[String] and
    (__ \ 'firstInvitedAt).formatNullable(DateTimeJsonFormat) and
    (__ \ 'score).format[Double]
  )(OrganizationInviteRecommendation.apply, unlift(OrganizationInviteRecommendation.unapply))
}

case class IrrelevantPeopleForUser(
  userId: Id[User],
  irrelevantUsers: Set[Id[User]],
  irrelevantFacebookAccounts: Set[Id[SocialUserInfo]],
  irrelevantLinkedInAccounts: Set[Id[SocialUserInfo]],
  irrelevantEmailAccounts: Set[Id[EmailAccountInfo]])

object IrrelevantPeopleForUser {
  implicit val format = Json.format[IrrelevantPeopleForUser]
  def empty(userId: Id[User]) = IrrelevantPeopleForUser(userId, Set.empty, Set.empty, Set.empty, Set.empty)
}

case class IrrelevantPeopleForOrg(
  orgId: Id[Organization],
  irrelevantUsers: Set[Id[User]],
  irrelevantEmailAccounts: Set[Id[EmailAccountInfo]])

object IrrelevantPeopleForOrg {
  implicit val format = Json.format[IrrelevantPeopleForOrg]
  def empty(orgId: Id[Organization]) = IrrelevantPeopleForOrg(orgId, Set.empty, Set.empty)
}
