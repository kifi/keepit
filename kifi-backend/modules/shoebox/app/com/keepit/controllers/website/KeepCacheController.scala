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
      case Some(article) =>

        val displayUrl = clean(if (article.url.length > 55) {
          article.url.take(30) + "…" + article.url.takeRight(15)
        } else article.url)

        val titleStr = article.content.title.map { title =>
          s"""<title>$title • Kifi</title>"""
        }.getOrElse(s"""<title>$displayUrl • Kifi</title>""")
        val headerStr = article.content.title.map { title =>
          s"""<header><h1>${clean(title)}</h1></header>"""
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
          val original = Some(s"""<a href="${clean(article.url)}">Original</a>""")
          val code = Seq(author, date, original).flatten.mkString(" • ")
          s"""<address>$code</address>"""
        }

        val content = article.content.rawContent.get

        val footer = {
          val fullUrl = clean(article.url)
          s"""<hr class="fin"><footer>Fetched on ${article.createdAt.toStandardDateString} from <a href="$fullUrl">$displayUrl</a> by Kifi for you. This is your personal page.</footer>"""
        }

        val page = Html(
          s"""
<!doctype html>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="/assets/cached/reader.css">

$titleStr

$headerStr
$byLine

<hr>

<div id="content">
$content
</div>

$footer

<br><hr><br>Full fetch article:<br>

${article.content.contentType}

${article.content.description}

${article.content.embedlyKeywords}

${article.content.entities.map(_.name)}

${article.content.images}

${article.content.keywords}

${article.content.language}

${article.content.media}

""")
        Ok(page).withHeaders("Content-Security-Policy-Report-Only" -> "default-src 'none'; img-src *; style-src 'unsafe-inline' *.kifi.com")
      case _ => NotFound
    }
  }

  private def clean(str: String): String = {
    HtmlFormat.escape(str).body
  }

}
