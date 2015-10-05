package com.keepit.model

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.ImagePath
import com.keepit.social.BasicUser
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.strings._

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
    orgConfig: ExternalOrganizationConfiguration) {
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
    (__ \ 'orgConfig).write[ExternalOrganizationConfiguration]
  )(unlift(OrganizationInfo.unapply))
}

case class OrganizationMembershipInfo(role: OrganizationRole)
object OrganizationMembershipInfo {
  implicit val defaultWrites = new Writes[OrganizationMembershipInfo] {
    def writes(o: OrganizationMembershipInfo): JsValue = Json.obj("role" -> o.role)
  }

  val testReads = new Reads[OrganizationMembershipInfo] {
    def reads(json: JsValue): JsResult[OrganizationMembershipInfo] = (json \ "role").validate[OrganizationRole].map(OrganizationMembershipInfo.apply)
  }
}

case class OrganizationViewerInfo(
  invite: Option[OrganizationInviteInfo],
  permissions: Set[OrganizationPermission],
  membership: Option[OrganizationMembershipInfo])
object OrganizationViewerInfo {
  implicit val defaultWrites: Writes[OrganizationViewerInfo] = (
    (__ \ 'invite).writeNullable[OrganizationInviteInfo] and
    (__ \ 'permissions).write[Set[OrganizationPermission]] and
    (__ \ 'membership).writeNullable[OrganizationMembershipInfo]
  )(unlift(OrganizationViewerInfo.unapply))

  val testReads: Reads[OrganizationViewerInfo] = (
    (__ \ 'invite).readNullable[OrganizationInviteInfo](OrganizationInviteInfo.testReads) and
    (__ \ 'permissions).read[Set[OrganizationPermission]] and
    (__ \ 'membership).readNullable[OrganizationMembershipInfo](OrganizationMembershipInfo.testReads)
  )(OrganizationViewerInfo.apply _)
}

case class OrganizationInviteInfo(
  inviter: BasicUser,
  lastInvited: DateTime)
object OrganizationInviteInfo {
  implicit val defaultWrites: Writes[OrganizationInviteInfo] = (
    (__ \ 'inviter).write[BasicUser] and
    (__ \ 'lastInvited).write[DateTime])(unlift(OrganizationInviteInfo.unapply))
  val testReads: Reads[OrganizationInviteInfo] = (
    (__ \ 'inviter).read[BasicUser] and
    (__ \ 'lastInvited).read[DateTime]
  )(OrganizationInviteInfo.apply _)
  def fromInvite(invite: OrganizationInvite, inviter: BasicUser): OrganizationInviteInfo = {
    OrganizationInviteInfo(inviter, invite.createdAt)
  }
}

case class BasicOrganizationView(
  basicOrganization: BasicOrganization,
  viewerInfo: OrganizationViewerInfo)
object BasicOrganizationView {
  implicit val defaultWrites: Writes[BasicOrganizationView] = new Writes[BasicOrganizationView] {
    def writes(o: BasicOrganizationView) = Json.toJson(o.basicOrganization).as[JsObject] + ("viewer" -> Json.toJson(o.viewerInfo))
  }

  val testReads: Reads[BasicOrganizationView] = (
    __.read[BasicOrganization] and
    (__ \ "viewer").read[OrganizationViewerInfo](OrganizationViewerInfo.testReads)
  )(BasicOrganizationView.apply _)
}

case class OrganizationView(
  organizationInfo: OrganizationInfo,
  viewerInfo: OrganizationViewerInfo)

object OrganizationView {
  val defaultWrites: Writes[OrganizationView] = new Writes[OrganizationView] {
    def writes(o: OrganizationView) = Json.obj("organization" -> OrganizationInfo.defaultWrites.writes(o.organizationInfo),
      "viewer" -> OrganizationViewerInfo.defaultWrites.writes(o.viewerInfo))
  }

  val embeddedMembershipWrites: Writes[OrganizationView] = new Writes[OrganizationView] {
    def writes(o: OrganizationView) = OrganizationInfo.defaultWrites.writes(o.organizationInfo).as[JsObject] ++
      Json.obj("viewer" -> OrganizationViewerInfo.defaultWrites.writes(o.viewerInfo).as[JsObject])
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
