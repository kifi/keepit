package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryCommander, PageCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.{ Library, LibraryAccess }
import play.api.libs.json._

class ExtLibraryController @Inject() (
    db: Database,
    actionAuthenticator: ActionAuthenticator,
    libraryCommander: LibraryCommander,
    basicUserRepo: BasicUserRepo,
    pageCommander: PageCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getLibraries() = JsonAction.authenticated { request =>
    val (libraries, _) = libraryCommander.getLibrariesByUser(request.userId)
    val libsCanKeepTo = libraries.filter(_._1 != LibraryAccess.READ_ONLY)
    val jsons = libsCanKeepTo.map { a =>
      val lib = a._2
      val owner = db.readOnlyMaster { implicit s => basicUserRepo.load(lib.ownerId) }
      Json.obj(
        "id" -> Library.publicId(lib.id.get).id,
        "name" -> lib.name,
        "path" -> Library.formatLibraryUrl(owner.username, owner.externalId, lib.slug),
        "visibility" -> Json.toJson(lib.visibility))
    }
    Ok(Json.obj("libraries" -> Json.toJson(jsons)))
  }

}
