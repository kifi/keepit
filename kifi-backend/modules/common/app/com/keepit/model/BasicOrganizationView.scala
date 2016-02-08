package com.keepit.model

import com.keepit.common.mail.EmailAddress
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationMembershipInfo(role: OrganizationRole)
object OrganizationMembershipInfo {
  implicit val format: Format[OrganizationMembershipInfo] = Format(
    Reads { j => (j.as[JsObject] \ "role").validate[OrganizationRole].map(OrganizationMembershipInfo(_)) },
    Writes { omi => Json.obj("role" -> omi.role) }
  )
}

case class OrganizationViewerInfo(
  invite: Option[OrganizationInviteInfo],
  emails: Set[EmailAddress],
  permissions: Set[OrganizationPermission],
  membership: Option[OrganizationMembershipInfo])
object OrganizationViewerInfo {
  implicit val internalFormat: OFormat[OrganizationViewerInfo] = (
    (__ \ 'invite).formatNullable[OrganizationInviteInfo] and
    (__ \ 'emails).format[Set[EmailAddress]] and
    (__ \ 'permissions).format[Set[OrganizationPermission]] and
    (__ \ 'membership).formatNullable[OrganizationMembershipInfo]
  )(OrganizationViewerInfo.apply, unlift(OrganizationViewerInfo.unapply))
}

case class OrganizationInviteInfo(
  inviter: BasicUser,
  email: Option[EmailAddress],
  lastInvited: DateTime)
object OrganizationInviteInfo {
  implicit val internalFormat: Format[OrganizationInviteInfo] = (
    (__ \ 'inviter).format[BasicUser] and
    (__ \ 'email).formatNullable[EmailAddress] and
    (__ \ 'lastInvited).format[DateTime]
  )(OrganizationInviteInfo.apply, unlift(OrganizationInviteInfo.unapply))

  def fromInvite(invite: OrganizationInvite, inviter: BasicUser): OrganizationInviteInfo = {
    // TODO(josh) should this be changed to the following?
    // OrganizationInviteInfo(inviter, invite.emailAddress, invite.lastReminderSentAt.getOrElse(invite.createdAt))
    OrganizationInviteInfo(inviter, invite.emailAddress, invite.createdAt)
  }
}

case class BasicOrganizationView(
  basicOrganization: BasicOrganization,
  viewerInfo: OrganizationViewerInfo)
object BasicOrganizationView {
  val reads: Reads[BasicOrganizationView] = (
    __.read[BasicOrganization] and
    (__ \ 'viewer).read[OrganizationViewerInfo]
  )(BasicOrganizationView.apply _)
  val writes: Writes[BasicOrganizationView] = Writes { bov =>
    BasicOrganization.defaultFormat.writes(bov.basicOrganization) ++ Json.obj("viewer" -> OrganizationViewerInfo.internalFormat.writes(bov.viewerInfo))
  }
  implicit val internalFormat = Format(reads, writes)
}
