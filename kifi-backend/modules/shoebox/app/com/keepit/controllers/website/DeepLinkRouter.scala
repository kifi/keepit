package com.keepit.controllers.website

import java.net.URLEncoder

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.PathCommander
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.path.Path
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.libs.json.{ Json, JsObject }

@Singleton
class DeepLinkRouter @Inject() (
    config: FortyTwoConfig,
    db: Database,
    userRepo: UserRepo,
    orgRepo: OrganizationRepo,
    libraryRepo: LibraryRepo,
    pathCommander: PathCommander,
    implicit val publicIdConfiguration: PublicIdConfiguration) {

  private def passwordReset(authToken: String): JsObject = Json.obj("t" -> DeepLinkType.PasswordReset, DeepLinkField.AuthToken -> authToken)
  def passwordResetDeepLink(authToken: String): String = deepLink(passwordReset(authToken))

  def deepLink(data: JsObject): String = config.applicationBaseUrl + "/redir?data=" + URLEncoder.encode(Json.stringify(data), "ascii")

  def generateRedirectUrl(data: JsObject): Option[Path] = {
    (data \ "t").as[String] match {
      case DeepLinkType.ViewHomepage =>
        Some(Path(""))
      case DeepLinkType.ViewFriends =>
        Some(Path("friends")) // view friends
      case DeepLinkType.PasswordReset =>
        val tokenOpt = (data \ DeepLinkField.AuthToken).asOpt[String]
        tokenOpt.map(token => Path(s"password/$token"))
      case DeepLinkType.InvitedLibraries =>
        Some(Path("me/libraries/invited"))
      case DeepLinkType.FriendRequest =>
        Some(Path("friends/requests"))
      case DeepLinkType.OrganizationInvite =>
        val orgIdOpt = (data \ DeepLinkField.OrganizationId).asOpt[PublicId[Organization]].flatMap(pubId => Organization.decodePublicId(pubId).toOption)
        val orgOpt = orgIdOpt.map { orgId => db.readOnlyReplica { implicit session => orgRepo.get(orgId) } }
        orgOpt.map(org => pathCommander.pathForOrganization(org))
      case DeepLinkType.LibraryRecommendation | DeepLinkType.LibraryInvite | DeepLinkType.LibraryView =>
        val libIdOpt = (data \ DeepLinkField.LibraryId).asOpt[PublicId[Library]].flatMap(pubId => Library.decodePublicId(pubId).toOption)
        val libOpt = libIdOpt.map { libId => db.readOnlyReplica { implicit session => libraryRepo.get(libId) } }
        libOpt.map(lib => pathCommander.pathForLibrary(lib))
      case DeepLinkType.NewFollower | DeepLinkType.UserView =>
        val userIdOpt = (data \ DeepLinkField.UserId).asOpt[ExternalId[User]]
        val userOpt = userIdOpt.map { extId => db.readOnlyReplica { implicit session => userRepo.getByExternalId(extId) } }
        userOpt.map(user => pathCommander.pathForUser(user))
      case _ => None
    }
  }
}

object DeepLinkType {
  val ViewHomepage = "vh"
  val ViewFriends = "vf"
  val PasswordReset = "pr"
  val InvitedLibraries = "il"
  val FriendRequest = "fr"
  val OrganizationInvite = "oi"
  val LibraryRecommendation = "lr"
  val LibraryInvite = "li"
  val LibraryView = "lv"
  val NewFollower = "nf"
  val UserView = "us"
}

object DeepLinkField {
  val OrganizationId = "oid"
  val LibraryId = "lid"
  val UserId = "uid"
  val AuthToken = "at"
}
