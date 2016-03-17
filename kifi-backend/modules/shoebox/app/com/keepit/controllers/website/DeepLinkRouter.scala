package com.keepit.controllers.website

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.PathCommander
import com.keepit.common.controller.{ UserRequest, MaybeUserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.path.Path
import com.keepit.model._
import play.api.libs.json.{ Json, JsObject }
import com.keepit.common.http._
import play.utils.UriEncoding

case class DeepLinkRedirect(url: String, externalLocator: Option[String] = None)

@ImplementedBy(classOf[DeepLinkRouterImpl])
trait DeepLinkRouter {
  def generateRedirect(data: JsObject, request: MaybeUserRequest[_]): Option[DeepLinkRedirect] // use when the user's state determines the redirect
  def generateRedirect(data: JsObject): Option[DeepLinkRedirect]
}

@Singleton
class DeepLinkRouterImpl @Inject() (
    db: Database,
    userRepo: UserRepo,
    orgRepo: OrganizationRepo,
    libraryRepo: LibraryRepo,
    uriRepo: NormalizedURIRepo,
    keepRepo: KeepRepo,
    pathCommander: PathCommander,
    implicit val publicIdConfiguration: PublicIdConfiguration) extends DeepLinkRouter {

  def generateRedirect(data: JsObject, request: MaybeUserRequest[_]): Option[DeepLinkRedirect] = {
    lazy val redirectToKeepPage = request match {
      case ur: UserRequest[_] => ur.kifiInstallationId.isEmpty || !ur.userAgentOpt.exists(_.canRunExtensionIfUpToDate)
      case _ => (data \ DeepLinkField.AuthToken).asOpt[String].isDefined
    }

    (data \ "t").asOpt[String].flatMap {
      case DeepLinkType.DiscussionView =>
        generateDiscussionViewRedirect(data, redirectToKeepPage = redirectToKeepPage)
      case _ =>
        generateRedirectUrl(data).map(url => DeepLinkRedirect(url = url, externalLocator = None))
    }
  }

  def generateRedirect(data: JsObject): Option[DeepLinkRedirect] = {
    (data \ "t").asOpt[String].flatMap {
      case DeepLinkType.DiscussionView =>
        generateDiscussionViewRedirect(data, redirectToKeepPage = false)
      case _ =>
        generateRedirectUrl(data).map(url => DeepLinkRedirect(url = url, externalLocator = None))
    }
  }

  def generateDiscussionViewRedirect(data: JsObject, redirectToKeepPage: Boolean): Option[DeepLinkRedirect] = {
    val keepPubIdOpt = (data \ DeepLinkField.KeepId).asOpt[PublicId[Keep]]
    val keepIdOpt = keepPubIdOpt.flatMap(kid => Keep.decodePublicIdStr(kid.id).toOption)
    for {
      keepId <- keepIdOpt
      keep <- db.readOnlyReplica(implicit s => keepRepo.getOption(keepId))
      accessTokenOpt = (data \ DeepLinkField.AuthToken).asOpt[String]
    } yield {
      val url = {
        if (redirectToKeepPage) keep.path.relative + accessTokenOpt.map(token => s"?authToken=$token").getOrElse("")
        else keep.url
      }
      DeepLinkRedirect(url, Some(s"/messages/${Keep.publicId(keep.id.get).id}").filterNot(_ => redirectToKeepPage))
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
      case DeepLinkType.OrganizationInvite | DeepLinkType.OrganizationView =>
        val orgIdOpt = (data \ DeepLinkField.OrganizationId).asOpt[PublicId[Organization]].flatMap(pubId => Organization.decodePublicId(pubId).toOption)
        val orgOpt = orgIdOpt.map { orgId => db.readOnlyReplica { implicit session => orgRepo.get(orgId) } }
        val authTokenOpt = (data \ DeepLinkField.AuthToken).asOpt[String]
        orgOpt.map { org =>
          pathCommander.pathForOrganization(org).absolute + authTokenOpt.map(at => s"?authToken=$at").getOrElse("")
        }
      case DeepLinkType.LibraryRecommendation | DeepLinkType.LibraryInvite | DeepLinkType.LibraryView =>
        val libIdOpt = (data \ DeepLinkField.LibraryId).asOpt[PublicId[Library]].flatMap(pubId => Library.decodePublicId(pubId).toOption)
        val libOpt = libIdOpt.map { libId => db.readOnlyReplica { implicit session => libraryRepo.get(libId) } }
        val authTokenOpt = (data \ DeepLinkField.AuthToken).asOpt[String]
        libOpt.map { lib =>
          pathCommander.pathForLibrary(lib).absolute + authTokenOpt.map(at => s"?authToken=$at").getOrElse("")
        }
      case DeepLinkType.NewFollower | DeepLinkType.UserView =>
        val userIdOpt = (data \ DeepLinkField.UserId).asOpt[ExternalId[User]]
        val userOpt = userIdOpt.map { extId => db.readOnlyReplica { implicit session => userRepo.getByExternalId(extId) } }
        userOpt.map(user => pathCommander.pathForUser(user).absolute)
      case _ => None
    }
  }
}

object DeepLinkRouter {
  def generateMobileDeeplink(data: JsObject): String = s"kifi://open?data=${UriEncoding.encodePathSegment(Json.stringify(data), "ascii")}"

  def libraryLink(libId: PublicId[Library], authToken: Option[String]): JsObject = Json.obj("t" -> DeepLinkType.LibraryView, DeepLinkField.LibraryId -> libId.id) ++ authTokenField(authToken)
  def organizationLink(orgId: PublicId[Organization], authToken: Option[String]): JsObject = Json.obj("t" -> DeepLinkType.OrganizationView, DeepLinkField.OrganizationId -> orgId.id) ++ authTokenField(authToken)
  def keepLink(keepId: PublicId[Keep], uriId: ExternalId[NormalizedURI], authToken: Option[String]): JsObject = Json.obj("t" -> DeepLinkType.DiscussionView, DeepLinkField.KeepId -> keepId.id, DeepLinkField.UriId -> uriId.id) ++ authTokenField(authToken)
  def userLink(userId: ExternalId[User]): JsObject = Json.obj("t" -> DeepLinkType.UserView, DeepLinkField.UserId -> userId.id)

  private def authTokenField(authTokenOpt: Option[String]) = authTokenOpt.map(t => Json.obj(DeepLinkField.AuthToken -> t)).getOrElse(Json.obj(Seq.empty: _*))

}

object DeepLinkType {
  val ViewHomepage = "vh"
  val ViewFriends = "vf"
  val InvitedLibraries = "il"
  val FriendRequest = "fr"
  val OrganizationInvite = "oi"
  val OrganizationView = "ov"
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
  val KeepId = "id"
  val UriId = "uri"
}
