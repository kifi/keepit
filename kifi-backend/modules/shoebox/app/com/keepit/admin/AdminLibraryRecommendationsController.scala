package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.{ RecommendationSubSource, RecommendationSource }
import com.keepit.model.{ LibraryRepo, User, UserRepo }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsString, JsArray, Json }
import views.html

class AdminLibraryRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    libRepo: LibraryRepo,
    libCommander: LibraryCommander,
    userRepo: UserRepo,
    db: Database,
    curator: CuratorServiceClient) extends AdminUserActions with Logging {

  def index() = AdminUserAction { implicit request =>
    Ok(html.admin.curator.librecos.index())
  }

  def view() = AdminUserAction.async { implicit request =>
    val userId = request.request.getQueryString("userId")
      .filter(_.nonEmpty) map (s => Id[User](s.toInt)) getOrElse request.userId
    val recosF = curator.topLibraryRecos(userId, Some(100)) map { libRecos =>
      val libIds = libRecos.map(_.libraryId)
      val libInfos = (libIds zip libCommander.getLibrarySummaries(libIds)).toMap

      libRecos map { libReco =>
        val prettyExplain = libReco.explain.split("-").toSeq.map { s =>
          val parts = s.split(":")
          val key = parts(0) match {
            case "s" => "social"
            case "i" => "interest"
            case "r" => "recency"
            case "p" => "popularity"
            case "si" => "size"
            case "c" => "content"
            case x => x
          }
          (key, parts(1))
        }.toMap

        val library = libInfos(libReco.libraryId)
        Json.obj(
          "libId" -> libReco.libraryId,
          "link" -> JsString("https://www.kifi.com" + library.url),
          "libInfo" -> Json.toJson(library),
          "score" -> libReco.masterScore,
          "explain" -> prettyExplain
        )
      }
    }

    recosF map { jsObjs => Ok(JsArray(jsObjs)) }
  }

}
