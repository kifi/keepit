package com.keepit.controllers.admin

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber

import akka.util.duration._
import java.util.concurrent.TimeUnit

import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.controllers.CommonActions._
import com.keepit.inject._
import com.keepit.scraper.ScraperPlugin
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.search.ArticleStore
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.model.Bookmark
import com.keepit.common.healthcheck.BabysitterTimeout
import org.joda.time.LocalDate
import org.joda.time.DateTimeZone
import com.keepit.model._
import com.keepit.common.net.URINormalizer

/**
 * Charts, etc.
 */
object UrlController extends FortyTwoController {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminHtmlAction { implicit request =>
    Ok(views.html.adminDashboard())
  }

  case class ChangedURL(context: String, url: String, from: Option[Id[URL]], to: Id[URL])
  case class ChangedNormURI(context: String, url: String, from: Option[Id[NormalizedURI]], to: Id[NormalizedURI])

  def grandfathering(readOnly: Boolean = true) = AdminHtmlAction { implicit request =>
    // Creates urlId connections where they do not exist, and verifies that references to URL are correct

    val changedURLs = scala.collection.mutable.MutableList[ChangedURL]()
    val (bookmarkCount, commentsCount) = CX.withConnection { implicit conn =>

      val bookmarks = Bookmark.all
      val bookmarkCount = bookmarks.size

      bookmarks map { bookmark =>
        val urlObj = URL.get(bookmark.url).getOrElse{
          val u = URL(bookmark.url, bookmark.uriId)
          if(!readOnly) u.save
          else u
        }
        bookmark.urlId match {
          case Some(id) if id.id == urlObj.id.map(_.id).getOrElse(0) =>
          case _ =>
            changedURLs += ChangedURL("bookmark-url", bookmark.url, bookmark.urlId, urlObj.id.getOrElse(Id[URL](0)) )
            if(!readOnly) bookmark.withUrlId(urlObj.id.get).save
        }
      }

      val comments = Comment.all
      val commentsCount = comments.size

      comments map { comment =>
        val normUri = NormalizedURI.get(comment.uriId)
        val urlObj = URL.get(normUri.url).getOrElse {
          val u = URL(normUri.url, normUri.id.get)
          if(!readOnly) u.save
          else u
        }
        comment.urlId match {
          case Some(id) if id.id == urlObj.id.map(_.id).getOrElse(0) =>
          case None =>
            changedURLs += ChangedURL("comment-url", normUri.url, comment.urlId, urlObj.id.getOrElse(Id[URL](0)) )
            if(!readOnly) comment.withUrlId(urlObj.id.get).save
        }
      }

      val follows = Follow.all
      val followsCount = follows.size

      follows map { follow =>
        val normUri = NormalizedURI.get(follow.uriId)
        val urlObj = URL.get(normUri.url).getOrElse {
          val u = URL(normUri.url, normUri.id.get)
          if(!readOnly) u.save
          else u
        }
        follow.urlId match {
          case Some(id) if id.id == urlObj.id.map(_.id).getOrElse(0) =>
          case None =>
            changedURLs += ChangedURL("follow-url", normUri.url, follow.urlId, urlObj.id.getOrElse(Id[URL](0)) )
            if(!readOnly) follow.withUrlId(urlObj.id.get).save
        }
      }

      val deeps = DeepLink.all
      val deepsCount = deeps.size

      deeps map { deeps =>
        val normUri = NormalizedURI.get(deeps.uriId.get)
        val urlObj = URL.get(normUri.url).getOrElse {
          val u = URL(normUri.url, normUri.id.get)
          if(!readOnly) u.save
          else u
        }
        deeps.urlId match {
          case Some(id) if id.id == urlObj.id.map(_.id).getOrElse(0) =>
          case None =>
            changedURLs += ChangedURL("deep-url", normUri.url, deeps.urlId, urlObj.id.getOrElse(Id[URL](0)) )
            if(!readOnly) deeps.withUrlId(urlObj.id.get).save
        }
      }

      (bookmarkCount, commentsCount)
    }
    val out = changedURLs.map { u =>
      "%s,%s,%s,%s".format(u.context,u.url, u.from.map(s=>s.id.toString).getOrElse(""), u.to.id)
    }.mkString("\n")
    Ok(bookmarkCount + " " + commentsCount + "\n\n\n" + out)
  }

  def renormalize(readOnly: Boolean = true) = AdminHtmlAction { implicit request =>
    // Creates urlId connections where they do not exist, and verifies that references to URL are correct
    val changedURIs = scala.collection.mutable.MutableList[ChangedNormURI]()
    val (bookmarkCount) = CX.withConnection { implicit conn =>

      val bookmarks = Bookmark.all
      val bookmarkCount = bookmarks.size

      bookmarks map { bookmark =>
        val currNormURI = NormalizedURI.get(bookmark.uriId)
        val urlObj = bookmark.urlId match {
          case Some(uu) => URL.get(uu)
          case None =>
            val u = URL(bookmark.url, currNormURI.id.get)
            if (!readOnly) u.save
            else u
        }
        val renormURI = NormalizedURI.getByNormalizedUrl(urlObj.url).getOrElse(NormalizedURI(urlObj.url).save)
        if (currNormURI.id != renormURI.id) {
          changedURIs += ChangedNormURI("bookmark-nuri", urlObj.url, currNormURI.id, renormURI.id.get)
          if(!readOnly) {
            urlObj.withHistory(URLHistory(renormURI.id.get,URLHistoryCause.SPLIT)).save
            bookmark.withNormUriId(renormURI.id.get).save
          }
        }
      }

      val comments = Comment.all
      val commentCount = comments.size

      comments map { comment =>
        val currNormURI = NormalizedURI.get(comment.uriId)
        val urlObj = comment.urlId match {
          case Some(uu) => URL.get(uu)
          case None =>
            val u = URL(currNormURI.url, currNormURI.id.get)
            if (!readOnly) u.save
            else u
        }
        val renormURI = NormalizedURI.getByNormalizedUrl(urlObj.url).getOrElse(NormalizedURI(urlObj.url).save)
        if (currNormURI.id != renormURI.id) {
          changedURIs += ChangedNormURI("comment-nuri", urlObj.url, currNormURI.id, renormURI.id.get)
          if(!readOnly) {
            urlObj.withHistory(URLHistory(renormURI.id.get,URLHistoryCause.SPLIT)).save
            comment.withNormUriId(renormURI.id.get).save
          }
        }
      }

      (bookmarkCount)
    }
    val out = changedURIs.map { u =>
      "%s,%s,%s,%s".format(u.context,u.url, u.from.map(s=>s.id.toString).getOrElse(""), u.to.id)
    }.mkString("\n")
    Ok(out)
  }

}
