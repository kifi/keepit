package com.keepit.controllers.website

import java.net.URLEncoder

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.PathCommander
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.path.Path
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.libs.json.{ Json, JsObject }

case class DeepLinkRedirect(url: String, externalLocator: Option[String] = None)

@ImplementedBy(classOf[DeepLinkRouterImpl])
trait DeepLinkRouter {
  def generateRedirect(data: JsObject): Option[DeepLinkRedirect]
}

@Singleton
class DeepLinkRouterImpl @Inject() (
    config: FortyTwoConfig,
    db: Database,
    userRepo: UserRepo,
    orgRepo: OrganizationRepo,
    libraryRepo: LibraryRepo,
    uriRepo: NormalizedURIRepo,
    pathCommander: PathCommander,
    implicit val publicIdConfiguration: PublicIdConfiguration) extends DeepLinkRouter {

  private def deepLink(data: JsObject): String = config.applicationBaseUrl + "/redir?data=" + URLEncoder.encode(Json.stringify(data), "ascii")

  def generateRedirect(data: JsObject): Option[DeepLinkRedirect] = {
    (data \ "t").asOpt[String].flatMap {
      case DeepLinkType.DiscussionView =>
        val uriIdOpt = (data \ DeepLinkField.UriId).asOpt[ExternalId[NormalizedURI]]
        val uriOpt = uriIdOpt.flatMap { uriId => db.readOnlyReplica { implicit session => uriRepo.getOpt(uriId) } }
        val messageIdOpt = (data \ DeepLinkField.MessageThreadId).asOpt[String]
        for {
          uri <- uriOpt
          messageId <- messageIdOpt
        } yield DeepLinkRedirect(uri.url, Some(s"/messages/$messageId"))
      case _ =>
        generateRedirectUrl(data).map(DeepLinkRedirect(_, externalLocator = None))
    }
  }

  def generateRedirectUrl(data: JsObject): Option[String] = {
    (data \ "t").asOpt[String].flatMap {
      case DeepLinkType.ViewHomepage =>
        Some(Path("").absolute)
      case DeepLinkType.ViewFriends =>
        Some(Path("friends").absolute)
      case DeepLinkType.InvitedLibraries =>
        Some(Path("me/libraries/invited").absolute)
      case DeepLinkType.FriendRequest =>
        Some(Path("friends/requests").absolute)
      case DeepLinkType.OrganizationInvite =>
        val orgIdOpt = (data \ DeepLinkField.OrganizationId).asOpt[PublicId[Organization]].flatMap(pubId => Organization.decodePublicId(pubId).toOption)
        val orgOpt = orgIdOpt.map { orgId => db.readOnlyReplica { implicit session => orgRepo.get(orgId) } }
        val authTokenOpt = (data \ DeepLinkField.AuthToken).asOpt[String]
        orgOpt.map { org =>
          pathCommander.pathForOrganization(org).absolute + authTokenOpt.map(at => s"&authToken=$at").getOrElse("")
        }
      case DeepLinkType.LibraryRecommendation | DeepLinkType.LibraryInvite | DeepLinkType.LibraryView =>
        val libIdOpt = (data \ DeepLinkField.LibraryId).asOpt[PublicId[Library]].flatMap(pubId => Library.decodePublicId(pubId).toOption)
        val libOpt = libIdOpt.map { libId => db.readOnlyReplica { implicit session => libraryRepo.get(libId) } }
        val authTokenOpt = (data \ DeepLinkField.AuthToken).asOpt[String]
        libOpt.map { lib =>
          pathCommander.pathForLibrary(lib).absolute + authTokenOpt.map(at => s"&authToken=$at").getOrElse("")
        }
      case DeepLinkType.NewFollower | DeepLinkType.UserView =>
        val userIdOpt = (data \ DeepLinkField.UserId).asOpt[ExternalId[User]]
        val userOpt = userIdOpt.map { extId => db.readOnlyReplica { implicit session => userRepo.getByExternalId(extId) } }
        userOpt.map(user => pathCommander.pathForUser(user).absolute)
      case _ => None
    }
  }
}

object DeepLinkType {
  val ViewHomepage = "vh"
  val ViewFriends = "vf"
  val InvitedLibraries = "il"
  val FriendRequest = "fr"
  val OrganizationInvite = "oi"
  val LibraryRecommendation = "lr"
  val LibraryInvite = "li"
  val LibraryView = "lv"
  val NewFollower = "nf"
  val UserView = "us"
  val DiscussionView = "m"
}

object DeepLinkField {
  val OrganizationId = "oid"
  val LibraryId = "lid"
  val UserId = "uid"
  val AuthToken = "at"
  val MessageThreadId = "id"
  val UriId = "uri"
}
