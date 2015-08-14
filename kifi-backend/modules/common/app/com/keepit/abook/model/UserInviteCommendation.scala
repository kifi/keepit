package com.keepit.abook.model

import com.keepit.common.db._
import com.keepit.model._
import com.keepit.social.{ SocialId, SocialNetworkType }
import com.kifi.macros.json

import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.json.EitherFormat

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

@json
case class OrganizationUserMayKnow(
  orgId: Id[Organization],
  score: Double)

case class OrganizationInviteRecommendation(
  identifier: Either[Id[User], EmailAddress],
  name: Option[String],
  score: Double)

object OrganizationInviteRecommendation {
  implicit val eitherFormat = EitherFormat[Id[User], EmailAddress]
  implicit val format = (
    (__ \ 'target).format[Either[Id[User], EmailAddress]] and
    (__ \ 'name).formatNullable[String] and
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
