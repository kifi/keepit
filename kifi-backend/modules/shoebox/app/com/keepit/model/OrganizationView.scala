package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.net.URI
import com.keepit.common.store.ImagePath
import com.keepit.model.OrganizationModifications.SiteModification
import com.keepit.slack.{ OrganizationSlackTeamInfo, OrganizationSlackInfo }
import com.keepit.social.BasicUser
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
    config: Option[ExternalOrganizationConfiguration],
    slackTeam: Option[OrganizationSlackTeamInfo],
    experiments: Seq[OrganizationExperimentType]) {
  def toBasicOrganization: BasicOrganization = BasicOrganization(this.orgId, this.ownerId, this.handle, this.name, this.description, this.avatarPath)
}
object OrganizationInfo {
  private implicit val exTypeFormat = OrganizationExperimentType.format

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
    (__ \ 'config).writeNullable[ExternalOrganizationConfiguration] and
    (__ \ 'slackTeam).writeNullable[OrganizationSlackTeamInfo] and
    (__ \ 'experiments).write[Seq[OrganizationExperimentType]]
  )(unlift(OrganizationInfo.unapply))
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

case class OrganizationInitialValues(name: String, description: Option[String] = None, rawSite: Option[String] = None) {
  def asOrganizationModifications: OrganizationModifications = {
    OrganizationModifications(
      name = Some(name),
      description = description,
      rawSite = rawSite
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
    rawSite: Option[String] = None) {
  def site: Option[SiteModification] = rawSite.map(SiteModification(_))
}
object OrganizationModifications {
  private val defaultReads: Reads[OrganizationModifications] = (
    (__ \ 'name).readNullable[String] and
    (__ \ 'description).readNullable[String] and
    (__ \ 'site).readNullable[String]
  )(OrganizationModifications.apply _)

  val website = defaultReads
  val mobileV1 = defaultReads

  trait SiteModification {
    val value: Option[String]
    def isValid: Boolean
  }
  object SiteModification {
    case object Remove extends SiteModification {
      val value = None
      def isValid = true
    }
    case class Site(url: String) extends SiteModification {
      val value = Some(url)
      def isValid = URI.parse(url).isSuccess
    }

    def apply(str: String): SiteModification = str match {
      case emptySite if emptySite.isEmpty => SiteModification.Remove
      case validSite if validSite.matches("^https?://.*") => Site(validSite)
      case noProtocolSite => Site("http://" + noProtocolSite)
    }
  }
}
