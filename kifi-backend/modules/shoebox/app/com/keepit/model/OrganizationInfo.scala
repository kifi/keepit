package com.keepit.model

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.ImagePath
import com.keepit.social.BasicUser
import com.kifi.macros.json
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
  val defaultWrites: Writes[OrganizationInfo] = (
    (__ \ 'id).write[PublicId[Organization]] and
    (__ \ 'ownerId).write[ExternalId[User]] and
    (__ \ 'handle).write[OrganizationHandle] and
    (__ \ 'name).write[String] and
    (__ \ 'description).writeNullable[String] and
    (__ \ 'site).writeNullable[String] and
    (__ \ 'avatarPath).writeNullable[ImagePath] and
    (__ \ 'members).write[Seq[BasicUser]] and
    (__ \ 'numMembers).write[Int] and
    (__ \ 'numLibraries).write[Int]
  )(unlift(OrganizationInfo.unapply))
}

case class MembershipInfo(
  isInvited: Boolean,
  permissions: Set[OrganizationPermission],
  role: Option[OrganizationRole])
object MembershipInfo {
  val defaultWrites: Writes[MembershipInfo] = (
    (__ \ 'isInvited).write[Boolean] and
    (__ \ 'permissions).write[Set[OrganizationPermission]] and
    (__ \ 'role).writeNullable[OrganizationRole]
  )(unlift(MembershipInfo.unapply))
}

case class OrganizationView(
  organizationInfo: OrganizationInfo,
  membershipInfo: MembershipInfo)

object OrganizationView {
  val websiteWrites: Writes[OrganizationView] = (
    (__ \ 'organizationInfo).write(OrganizationInfo.defaultWrites) and
    (__ \ 'membershipInfo).write(MembershipInfo.defaultWrites)
  )(unlift(OrganizationView.unapply))
  val mobileWrites = new Writes[OrganizationView] {
    def writes(o: OrganizationView) = OrganizationInfo.defaultWrites.writes(o.organizationInfo).as[JsObject] ++ MembershipInfo.defaultWrites.writes(o.membershipInfo).as[JsObject]
  }
  val defaultWrites = websiteWrites
}

// OrganizationCard should ONLY contain public information. No internal ids.
case class OrganizationCard(
  orgId: PublicId[Organization],
  ownerId: ExternalId[User],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  avatarPath: Option[ImagePath],
  numMembers: Int,
  numLibraries: Int)
object OrganizationCard {
  private val defaultWrites: Writes[OrganizationCard] = (
    (__ \ 'id).write[PublicId[Organization]] and
    (__ \ 'ownerId).write[ExternalId[User]] and
    (__ \ 'handle).write[OrganizationHandle] and
    (__ \ 'name).write[String] and
    (__ \ 'description).writeNullable[String] and
    (__ \ 'avatarPath).writeNullable[ImagePath] and
    (__ \ 'numMembers).write[Int] and
    (__ \ 'numLibraries).write[Int]
  )(unlift(OrganizationCard.unapply))

  val websiteWrites = defaultWrites
  val mobileWrites = defaultWrites
}

// OrganizationImageInfo and OrganizationNotificationInfo are strictly for use in the
// OrganizationInviteCommander to notify members when a new user joins their
// organization. I would like to get rid of them.
@json case class OrganizationImageInfo(path: ImagePath)
object OrganizationImageInfo {
  def createInfo(img: OrganizationAvatar) = OrganizationImageInfo(img.imagePath)
}
@json
case class OrganizationNotificationInfo(
  id: PublicId[Organization],
  name: String,
  handle: Option[PrimaryOrganizationHandle],
  image: Option[OrganizationImageInfo])
object OrganizationNotificationInfo {
  def fromOrganization(org: Organization, image: Option[OrganizationAvatar])(implicit config: PublicIdConfiguration): OrganizationNotificationInfo = {
    OrganizationNotificationInfo(Organization.publicId(org.id.get), org.name, org.handle, image.map(OrganizationImageInfo.createInfo))
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
    (__ \ 'site).readNullable[String]
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
    (__ \ 'site).readNullable[String]
  )(OrganizationModifications.apply _)

  val website = defaultReads
  val mobileV1 = defaultReads
}
