package com.keepit.controllers.website

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.PathCommander
import com.keepit.common.controller.{ UserRequest, MaybeUserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.path.Path
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.libs.json.{ Json, JsObject }
import com.keepit.common.http._

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
      case _ => true
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
    val uriIdOpt = (data \ DeepLinkField.UriId).asOpt[ExternalId[NormalizedURI]]
    val uriOpt = uriIdOpt.flatMap { uriId => db.readOnlyReplica { implicit session => uriRepo.getOpt(uriId) } }
    val keepPubIdOpt = (data \ DeepLinkField.KeepId).asOpt[PublicId[Keep]]
    val keepIdOpt = keepPubIdOpt.flatMap(kid => Keep.decodePublicIdStr(kid.id).toOption)
    val keepPageOpt = for {
      _ <- Some(()) if redirectToKeepPage
      keepId <- keepIdOpt
      keep <- db.readOnlyReplica(implicit s => keepRepo.getOption(keepId))
      accessTokenOpt = (data \ DeepLinkField.AuthToken).asOpt[String]
    } yield keep.path.relative + accessTokenOpt.map(token => s"?authToken=$token").getOrElse("")

    for {
      uri <- uriOpt
      keepPubId <- keepPubIdOpt
    } yield DeepLinkRedirect(keepPageOpt.getOrElse(uri.url), Some(s"/messages/${keepPubId.id}").filter(_ => keepPageOpt.isEmpty))
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
  def libraryLink(libId: PublicId[Library]): JsObject = Json.obj("t" -> DeepLinkType.LibraryView, DeepLinkField.LibraryId -> libId.id)
  def organizationLink(orgId: PublicId[Organization]): JsObject = Json.obj("t" -> DeepLinkType.OrganizationView, DeepLinkField.OrganizationId -> orgId.id)
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
