package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.PathCommander
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.path.Path
import com.keepit.model._
import play.api.libs.json.JsObject

@Singleton
class DeepLinkRouter @Inject() (
    db: Database,
    userRepo: UserRepo,
    orgRepo: OrganizationRepo,
    libraryRepo: LibraryRepo,
    pathCommander: PathCommander,
    implicit val publicIdConfiguration: PublicIdConfiguration) {

  def generateRedirectUrl(data: JsObject): Option[Path] = {
    (data \ "t").as[String] match {
      case "fr" => Some(Path("friends/requests"))
      case "lr" | "li" =>
        val libIdOpt = (data \ "lid").asOpt[PublicId[Library]].flatMap(pubId => Library.decodePublicId(pubId).toOption)
        val libOpt = libIdOpt.map { libId => db.readOnlyReplica { implicit session => libraryRepo.get(libId) } }
        libOpt.map(lib => pathCommander.pathForLibrary(lib))
      case "nf" | "us" =>
        val userIdOpt = (data \ "uid").asOpt[ExternalId[User]]
        val userOpt = userIdOpt.map { extId => db.readOnlyReplica { implicit session => userRepo.getByExternalId(extId) } }
        userOpt.map(user => pathCommander.pathForUser(user))
      case "m" => ??? // Is there any way to display a message on the website?
      case "ur" => ??? // is there any way to display unread message on the website?
      case _ => None
    }
  }
}
