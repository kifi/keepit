package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.CuratorServiceClient
import com.keepit.model.{ LibraryRepo, User }
import play.api.libs.json.{ JsArray, Json }
import views.html

import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }

import play.api.libs.concurrent.Execution.Implicits.defaultContext

class AdminLibraryRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    libRepo: LibraryRepo,
    db: Database,
    curator: CuratorServiceClient) extends AdminUserActions with Logging {

  def index() = AdminUserAction { implicit request =>
    Ok(html.admin.curator.librecos.index())
  }

  def view() = AdminUserAction.async { implicit request =>
    val body = request.body.asFormUrlEncoded.map(_.mapValues(_.head)) getOrElse Map.empty
    val userId = body.get("userId").map(s => Id[User](s.toInt)) getOrElse request.userId
    val recosF = curator.topLibraryRecos(userId) map { libRecos =>
      val libIds = libRecos.map(_.libraryId).toSet
      val libraries = db.readOnlyReplica { implicit s => libRepo.getLibraries(libIds) }

      libRecos map { libReco =>
        val prettyExplain = libReco.explain.split("-").toSeq.map { s =>
          val parts = s.split(":")
          (parts(0), parts(1))
        }.toMap

        val library = libraries(libReco.libraryId)
        Json.obj(
          "libId" -> libReco.libraryId,
          "name" -> library.name,
          "desc" -> library.description,
          "ownerId" -> library.ownerId,
          "score" -> libReco.masterScore,
          "explain" -> prettyExplain
        )
      }
    }

    recosF map { jsObjs => Ok(JsArray(jsObjs)) }
  }

}
