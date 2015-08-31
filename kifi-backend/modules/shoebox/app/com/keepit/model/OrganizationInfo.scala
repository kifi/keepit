package com.keepit.model

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.ImagePath
import com.keepit.social.BasicUser
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

// OrganizationView should ONLY contain public information. No internal ids.
case class OrganizationInfo(
  orgId: PublicId[Organization],
  ownerId: ExternalId[User],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  site: Option[String],
  avatarPath: Option[ImagePath],
  members: Seq[BasicUser],
  numMembers: Int,
  numLibraries: Int)
object OrganizationInfo {
  implicit val defaultFormat: Format[OrganizationInfo] = (
    (__ \ 'id).format[PublicId[Organization]] and
    (__ \ 'ownerId).format[ExternalId[User]] and
    (__ \ 'handle).format[OrganizationHandle] and
    (__ \ 'name).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'site).formatNullable[String] and
    (__ \ 'avatarPath).formatNullable[ImagePath] and
    (__ \ 'members).format[Seq[BasicUser]] and
    (__ \ 'numMembers).format[Int] and
    (__ \ 'numLibraries).format[Int]
  )(OrganizationInfo.apply, unlift(OrganizationInfo.unapply))
}

case class OrganizationMembershipInfo(
  isInvited: Boolean, // deprecated, kill and use invite.isDefined || invite != null instead
  invite: Option[OrganizationInviteInfo],
  permissions: Set[OrganizationPermission],
  role: Option[OrganizationRole])
object OrganizationMembershipInfo {
  implicit val defaultWrites: Writes[OrganizationMembershipInfo] = (
    (__ \ 'isInvited).write[Boolean] and
    (__ \ 'invite).writeNullable[OrganizationInviteInfo] and
    (__ \ 'permissions).write[Set[OrganizationPermission]] and
    (__ \ 'role).writeNullable[OrganizationRole]
  )(unlift(OrganizationMembershipInfo.unapply))
}

case class OrganizationInviteInfo(
  inviter: BasicUser,
  lastInvited: DateTime)
object OrganizationInviteInfo {
  implicit val defaultWrites: Writes[OrganizationInviteInfo] = (
    (__ \ 'inviter).write[BasicUser] and
    (__ \ 'lastInvited).write[DateTime])(unlift(OrganizationInviteInfo.unapply))
  def fromInvite(invite: OrganizationInvite, inviter: BasicUser): OrganizationInviteInfo = {
    OrganizationInviteInfo(inviter, invite.updatedAt)
  }
}

case class OrganizationView(
  organizationInfo: OrganizationInfo,
  membershipInfo: OrganizationMembershipInfo)

object OrganizationView {
  implicit val writes: Writes[OrganizationView] = new Writes[OrganizationView] {
    def writes(o: OrganizationView) = Json.obj("organization" -> OrganizationInfo.defaultFormat.writes(o.organizationInfo),
      "membership" -> OrganizationMembershipInfo.defaultWrites.writes(o.membershipInfo))
  }
}

object OrganizationNotificationInfoBuilder {
  def fromOrganization(org: Organization, image: Option[OrganizationAvatar])(implicit config: PublicIdConfiguration): OrganizationNotificationInfo = {
    OrganizationNotificationInfo(Organization.publicId(org.id.get), org.name, org.primaryHandle, image.map(_.imagePath))
  }
}

case class OrganizationInitialValues(name: String, description: Option[String] = None, site: Option[String] = None) {
  def asOrganizationModifications: OrganizationModifications = {
    OrganizationModifications(
      name = Some(name),
      description = description,
      site = site
    )
  }
}

object OrganizationInitialValues {
  private val defaultReads: Reads[OrganizationInitialValues] = (
    (__ \ 'name).read[String] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'site).readNullable[String].map {
      case Some(site) if "^https?://".r.findFirstMatchIn(site).isEmpty => Some("http://" + site)
      case Some(site) => Some(site)
      case None => None
    }
  )(OrganizationInitialValues.apply _)

  val website = defaultReads
  val mobileV1 = defaultReads
}

case class OrganizationModifications(
  name: Option[String] = None,
  description: Option[String] = None,
  basePermissions: Option[BasePermissions] = None,
  site: Option[String] = None)
object OrganizationModifications {
  private val defaultReads: Reads[OrganizationModifications] = (
    (__ \ 'name).readNullable[String] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'basePermissions).readNullable[BasePermissions] and
    (__ \ 'site).readNullable[String].map {
      case Some(site) if "^https?://".r.findFirstMatchIn(site).isEmpty => Some("http://" + site)
      case Some(site) => Some(site)
      case None => None
    }
  )(OrganizationModifications.apply _)

  val website = defaultReads
  val mobileV1 = defaultReads
}
