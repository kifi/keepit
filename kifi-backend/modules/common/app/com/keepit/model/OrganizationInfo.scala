package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.EmailAddress
import com.keepit.common.store.ImagePath
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationInfo(
    orgId: PublicId[Organization],
    ownerId: ExternalId[User],
    handle: OrganizationHandle,
    name: String,
    description: Option[String],
    site: Option[String],
    avatarPath: ImagePath,
    members: Seq[BasicUser],
    numMembers: Int,
    numLibraries: Int,
    config: ExternalOrganizationConfiguration) {
  def toBasicOrganization: BasicOrganization = BasicOrganization(this.orgId, this.ownerId, this.handle, this.name, this.description, this.avatarPath)
}
object OrganizationInfo {
  implicit val defaultWrites: Writes[OrganizationInfo] = (
    (__ \ 'id).write[PublicId[Organization]] and
    (__ \ 'ownerId).write[ExternalId[User]] and
    (__ \ 'handle).write[OrganizationHandle] and
    (__ \ 'name).write[String] and
    (__ \ 'description).writeNullable[String] and
    (__ \ 'site).writeNullable[String] and
    (__ \ 'avatarPath).write[ImagePath] and
    (__ \ 'members).write[Seq[BasicUser]] and
    (__ \ 'numMembers).write[Int] and
    (__ \ 'numLibraries).write[Int] and
    (__ \ 'config).write[ExternalOrganizationConfiguration]
  )(unlift(OrganizationInfo.unapply))
}

case class OrganizationMembershipInfo(role: OrganizationRole)
object OrganizationMembershipInfo {
  implicit val format: Format[OrganizationMembershipInfo] = Format(
    Reads { j => (j.as[JsObject] \ "role").validate[OrganizationRole].map(OrganizationMembershipInfo(_)) },
    Writes { omi => Json.obj("role" -> omi.role) }
  )
}

case class OrganizationViewerInfo(
  invite: Option[OrganizationInviteInfo],
  sharedEmails: Set[EmailAddress],
  permissions: Set[OrganizationPermission],
  membership: Option[OrganizationMembershipInfo])
object OrganizationViewerInfo {
  implicit val internalFormat: OFormat[OrganizationViewerInfo] = (
    (__ \ 'invite).formatNullable[OrganizationInviteInfo] and
    (__ \ 'sharedEmails).format[Set[EmailAddress]] and
    (__ \ 'permissions).format[Set[OrganizationPermission]] and
    (__ \ 'membership).formatNullable[OrganizationMembershipInfo]
  )(OrganizationViewerInfo.apply, unlift(OrganizationViewerInfo.unapply))
}

case class OrganizationInviteInfo(
  inviter: BasicUser,
  lastInvited: DateTime)
object OrganizationInviteInfo {
  implicit val internalFormat: Format[OrganizationInviteInfo] = (
    (__ \ 'inviter).format[BasicUser] and
    (__ \ 'lastInvited).format[DateTime]
  )(OrganizationInviteInfo.apply, unlift(OrganizationInviteInfo.unapply))

  def fromInvite(invite: OrganizationInvite, inviter: BasicUser): OrganizationInviteInfo = {
    OrganizationInviteInfo(inviter, invite.createdAt)
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

case class OrganizationView(
  organizationInfo: OrganizationInfo,
  viewerInfo: OrganizationViewerInfo)

object OrganizationView {
  val defaultWrites: Writes[OrganizationView] = Writes { o =>
    Json.obj("organization" -> o.organizationInfo, "viewer" -> o.viewerInfo)
  }

  val embeddedMembershipWrites: Writes[OrganizationView] = Writes { o =>
    Json.toJson(o.organizationInfo).as[JsObject] ++ Json.obj("viewer" -> o.viewerInfo)
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
  site: Option[String] = None)
object OrganizationModifications {
  private val defaultReads: Reads[OrganizationModifications] = (
    (__ \ 'name).readNullable[String] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'site).readNullable[String].map {
      case Some(site) if httpRegex.findFirstMatchIn(site).isEmpty => Some("http://" + site)
      case Some(site) => Some(site)
      case None => None
    }
  )(OrganizationModifications.apply _)

  val website = defaultReads
  val mobileV1 = defaultReads

  private val httpRegex = "^https?://".r
}
