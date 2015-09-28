package com.keepit.model

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.ImagePath
import com.keepit.social.BasicUser
import com.kifi.macros.json
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
    numLibraries: Int) {
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
    (__ \ 'numLibraries).write[Int]
  )(unlift(OrganizationInfo.unapply))

  val testReads: Reads[OrganizationInfo] = ( // for test-usage only
    (__ \ 'id).read[PublicId[Organization]] and
    (__ \ 'ownerId).read[ExternalId[User]] and
    (__ \ 'handle).read[OrganizationHandle] and
    (__ \ 'name).read[String] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'site).readNullable[String] and
    (__ \ 'avatarPath).read[ImagePath] and
    (__ \ 'members).read[Seq[BasicUser]] and
    (__ \ 'numMembers).read[Int] and
    (__ \ 'numLibraries).read[Int]
  )(OrganizationInfo.apply _)
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

  val testReads: Reads[OrganizationMembershipInfo] = (
    (__ \ 'isInvited).read[Boolean] and
    (__ \ 'invite).readNullable[OrganizationInviteInfo](OrganizationInviteInfo.testReads) and
    (__ \ 'permissions).read[Set[OrganizationPermission]] and
    (__ \ 'role).readNullable[OrganizationRole]
  )(OrganizationMembershipInfo.apply _)
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
  membershipInfo: OrganizationMembershipInfo)
object BasicOrganizationView {
  implicit val defaultWrites: Writes[BasicOrganizationView] = new Writes[BasicOrganizationView] {
    def writes(o: BasicOrganizationView) = Json.toJson(o.basicOrganization).as[JsObject] + ("membership" -> Json.toJson(o.membershipInfo))
  }

  val testReads: Reads[BasicOrganizationView] = (
    __.read[BasicOrganization] and
    (__ \ "membership").read[OrganizationMembershipInfo](OrganizationMembershipInfo.testReads)
  )(BasicOrganizationView.apply _)
}

case class OrganizationView(
  organizationInfo: OrganizationInfo,
  membershipInfo: OrganizationMembershipInfo)

object OrganizationView {
  val defaultWrites: Writes[OrganizationView] = new Writes[OrganizationView] {
    def writes(o: OrganizationView) = Json.obj("organization" -> OrganizationInfo.defaultWrites.writes(o.organizationInfo),
      "membership" -> OrganizationMembershipInfo.defaultWrites.writes(o.membershipInfo))
  }

  val embeddedMembershipWrites: Writes[OrganizationView] = new Writes[OrganizationView] {
    def writes(o: OrganizationView) = OrganizationInfo.defaultWrites.writes(o.organizationInfo).as[JsObject] ++
      Json.obj("membership" -> OrganizationMembershipInfo.defaultWrites.writes(o.membershipInfo).as[JsObject])
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
