package com.keepit.model

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.ImagePath
import com.keepit.social.BasicUser
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationGetResponse(
  organization: OrganizationView,
  viewerRelationship: Option[OrganizationViewerRelationship])
object OrganizationGetResponse {
  val defaultWrites: Writes[OrganizationGetResponse] = (
    (__ \ 'organization).write(OrganizationView.defaultWrites) and
    (__ \ 'orgViewerRelationship).writeNullable(OrganizationViewerRelationship.defaultWrites)
  )(unlift(OrganizationGetResponse.unapply))
}

// OrganizationView should ONLY contain public information. No internal ids.
case class OrganizationView(
  orgId: PublicId[Organization],
  ownerId: ExternalId[User],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  avatarPath: Option[ImagePath],
  members: Seq[BasicUser],
  numMembers: Int,
  numLibraries: Int)
object OrganizationView {
  val defaultWrites: Writes[OrganizationView] = (
    (__ \ 'id).write[PublicId[Organization]] and
    (__ \ 'ownerId).write[ExternalId[User]] and
    (__ \ 'handle).write[OrganizationHandle] and
    (__ \ 'name).write[String] and
    (__ \ 'description).writeNullable[String] and
    (__ \ 'avatarPath).writeNullable[ImagePath] and
    (__ \ 'members).write[Seq[BasicUser]] and
    (__ \ 'numMembers).write[Int] and
    (__ \ 'numLibraries).write[Int]
  )(unlift(OrganizationView.unapply))

  val website = defaultWrites
  val mobileV1 = defaultWrites
}

case class OrganizationViewerRelationship(
  isInvited: Boolean = false,
  role: Option[OrganizationRole],
  permissions: Set[OrganizationPermission])
object OrganizationViewerRelationship {
  val defaultWrites: Writes[OrganizationViewerRelationship] = (
    (__ \ 'isInvited).write[Boolean] and
    (__ \ 'role).writeNullable[OrganizationRole] and
    (__ \ 'permissions).write[Seq[OrganizationPermission]]
  )(unlift(OrganizationViewerRelationship.unapply))

  val website = defaultWrites
  val mobileV1 = defaultWrites
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

  val website = defaultWrites
  val mobileV1 = defaultWrites
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

case class OrganizationInitialValues(name: String, description: Option[String] = None) {
  def asOrganizationModifications: OrganizationModifications = {
    OrganizationModifications(
      name = Some(name),
      description = description
    )
  }
}
object OrganizationInitialValues {
  private val defaultReads: Reads[OrganizationInitialValues] = (
    (__ \ 'name).read[String] and
    (__ \ 'description).readNullable[String]
  )(OrganizationInitialValues.apply _)

  val website = defaultReads
  val mobileV1 = defaultReads

}

case class OrganizationModifications(
  name: Option[String] = None,
  description: Option[String] = None,
  basePermissions: Option[BasePermissions] = None)
object OrganizationModifications {
  private val defaultReads: Reads[OrganizationModifications] = (
    (__ \ 'name).readNullable[String] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'basePermissions).readNullable[BasePermissions]
  )(OrganizationModifications.apply _)

  val website = defaultReads
  val mobileV1 = defaultReads
}
