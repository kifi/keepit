package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, ShoeboxServiceController, UserActions }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.model.{ KeepRepo, Keep, NormalizedURIRepo }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.article.EmbedlyArticle
import play.twirl.api.{ HtmlFormat, Html }
import scala.concurrent.duration._
import com.keepit.common.time._
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

class KeepCacheController @Inject() (
    roverServiceClient: RoverServiceClient,
    db: Database,
    keepRepo: KeepRepo,
    normalizedUriRepo: NormalizedURIRepo,
    val userActionsHelper: UserActionsHelper,
    implicit val ec: ExecutionContext) extends UserActions with ShoeboxServiceController {

  def getCachedKeep(id: ExternalId[Keep]) = MaybeUserPage.async { request =>

    val nUrlOpt = db.readOnlyReplica { implicit session =>
      keepRepo.getOpt(id).map { keep =>
        normalizedUriRepo.get(keep.uriId).url
      }
    }
    nUrlOpt.map { nUrl =>
      roverServiceClient.getOrElseFetchRecentArticle(nUrl, 182.days)(EmbedlyArticle)
    }.getOrElse(Future.successful(None)).map {
      case Some(article) if article.content.rawContent.nonEmpty =>
        val titleStr = article.content.title.map { title =>
          s"""<h1>$title</h1>"""
        }.getOrElse("")
        val byLine = {
          val author = {
            if (article.content.authors.nonEmpty) {
              Some("By " + article.content.authors.map { a =>
                a.url match {
                  case Some(url) =>
                    s"""<a href="${clean(url)}">${clean(a.name)}</a>"""
                  case None =>
                    clean(a.name)
                }
              }.mkString(", "))
            } else None
          }
          val date = article.content.publishedAt.map(d => d.toStandardDateString)
          Seq(author, date).flatten.mkString(" • ")
        }

        val content = article.content.rawContent.get

        val page = Html(
          s"""
<!doctype html>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="/assets/cached/reader.css">

<title>${article.content.title.map(t => clean(t) + " • Kifi").getOrElse(s"Kifi Cache of ${clean(article.url)}")}</title>

<h6>Fetched on ${article.createdAt.toStandardDateString} from <a href="${clean(article.url)}">${clean(article.url)}</a> by Kifi.</h6>

$titleStr
$byLine

<hr>

$content""")
        Ok(page).withHeaders("Content-Security-Policy-Report-Only" -> "default-src 'none'")
      case _ => NotFound
    }
  }

  private def clean(str: String): String = {
    HtmlFormat.escape(str).body
  }

}
