package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, ShoeboxServiceController, UserActions }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.model.{ KeepRepo, Keep, NormalizedURIRepo }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.EmbedlyArticle
import play.twirl.api.Html
import scala.concurrent.duration._

import scala.concurrent.{ ExecutionContext, Future }

class KeepCacheController @Inject() (
    roverServiceClient: RoverServiceClient,
    db: Database,
    keepRepo: KeepRepo,
    normalizedUriRepo: NormalizedURIRepo,
    val userActionsHelper: UserActionsHelper,
    implicit val ec: ExecutionContext) extends UserActions with ShoeboxServiceController {

  def getCachedKeep(id: ExternalId[Keep]) = MaybeUserPage.async { request =>
    val nUriOpt = db.readOnlyReplica { implicit session =>
      keepRepo.getOpt(id).map { keep =>
        normalizedUriRepo.get(keep.uriId)
      }
    }
    nUriOpt.map { nUri =>
      roverServiceClient.getOrElseFetchRecentArticle(nUri.url, 182.days)(EmbedlyArticle).map {
        case Some(article) => Some(article.content.description.getOrElse("no desc") + article.content.content.getOrElse("no content"))
        case None => None
      }
    }.getOrElse(Future.successful(None)).map {
      case Some(articleHtml) =>
        Ok(Html(articleHtml)).withHeaders("Content-Security-Policy-Report-Only" -> "default 'none'")
      case None => NotFound
    }
  }

}
