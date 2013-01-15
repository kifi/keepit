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
import com.keepit.model._
import com.keepit.search.ArticleStore
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.time._
import com.keepit.common.healthcheck.BabysitterTimeout
import org.joda.time.LocalDate
import org.joda.time.DateTimeZone
import com.keepit.common.net.URINormalizer
import com.keepit.common.mail._
import play.api.libs.concurrent.Akka

/**
 * Charts, etc.
 */
object UrlController extends FortyTwoController {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminHtmlAction { implicit request =>
    Ok(views.html.adminDashboard())
  }

  case class ChangedNormURI(context: String, url: String, currURI: String, from: Long, to: Long, toUri: String)

  def renormalize(readOnly: Boolean = true, domain: Option[String] = None) = AdminHtmlAction { implicit request =>
    Akka.future {
      val result = doRenormalize(readOnly, domain).replaceAll("\n","\n<br>");
      val postOffice = inject[PostOffice]
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = "Renormalization Report", htmlBody = result, category = PostOffice.Categories.ADMIN))
    }
    Ok("Started! Will email %s".format(EmailAddresses.ENG))
  }

  def doRenormalize(readOnly: Boolean = true, domain: Option[String] = None) = {
    // Processes all models that reference a `NormalizedURI`, and renormalizes all URLs.
    val changedURIs = scala.collection.mutable.MutableList[ChangedNormURI]()
    val (bookmarkCount, commentCount, followsCount, deepsCount) = CX.withConnection { implicit conn =>

      val bookmarks = BookmarkCxRepo.all
      val bookmarkCount = bookmarks.size

      bookmarks map { bookmark =>
        val currNormURI = NormalizedURICxRepo.get(bookmark.uriId)
        val urlObj = bookmark.urlId match {
          case Some(uu) => URLCxRepo.get(uu)
          case None =>
            val u = URL(url = bookmark.url, normalizedUriId = currNormURI.id.get)
            if (!readOnly) u.save
            else u
        }
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") => Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            val (renormURI,reason) = NormalizedURICxRepo.getByNormalizedUrl(urlObj.url) match {
              case Some(u) => (u,URLHistoryCause.MERGE)
              case None => (NormalizedURIFactory(urlObj.url),URLHistoryCause.SPLIT)
            }

            if (currNormURI.url != renormURI.url) {
              changedURIs += ChangedNormURI("bookmark-nuri", urlObj.url, currNormURI.url, currNormURI.id.map(_.id).getOrElse(0L), renormURI.id.map(_.id).getOrElse(0L), renormURI.url)
              try {
                if(!readOnly) {
                  val savedNormURI = renormURI.save
                  urlObj.withNormURI(savedNormURI.id.get).withHistory(URLHistory(savedNormURI.id.get,reason)).save
                  bookmark.withNormUriId(renormURI.id.get).save
                }
              } catch {
                case ex: Throwable =>
                  log.error("URL Migration error",ex)
              }

            }
        }
      }

      val comments = CommentCxRepo.all
      val commentCount = comments.size

      comments map { comment =>
        val currNormURI = NormalizedURICxRepo.get(comment.uriId)
        val urlObj = comment.urlId match {
          case Some(uu) => URLCxRepo.get(uu)
          case None =>
            val u = URL(url = currNormURI.url, normalizedUriId = currNormURI.id.get)
            if (!readOnly) u.save
            else u
        }
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") => Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            val (renormURI,reason) = NormalizedURICxRepo.getByNormalizedUrl(urlObj.url) match {
              case Some(u) => (u,URLHistoryCause.MERGE)
              case None => (NormalizedURIFactory(urlObj.url),URLHistoryCause.SPLIT)
            }
            if (currNormURI.url != renormURI.url) {
              changedURIs += ChangedNormURI("comment-nuri", urlObj.url, currNormURI.url, currNormURI.id.map(_.id).getOrElse(0L), renormURI.id.map(_.id).getOrElse(0L), renormURI.url)
              if(!readOnly) {
                try {
                  val savedNormURI = renormURI.save
                  urlObj.withHistory(URLHistory(savedNormURI.id.get,reason)).save
                  comment.withNormUriId(renormURI.id.get).save
                } catch {
                  case ex: Throwable =>
                    log.error("URL Migration error",ex)
                }
              }
            }
        }
      }

      val follows = FollowCxRepo.all
      val followsCount = follows.size

      follows map { follow =>
        val currNormURI = NormalizedURICxRepo.get(follow.uriId)
        val urlObj = follow.urlId match {
          case Some(uu) => URLCxRepo.get(uu)
          case None =>
            val u = URL(url = currNormURI.url, normalizedUriId = currNormURI.id.get)
            if (!readOnly) u.save
            else u
        }
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") => Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            val (renormURI,reason) = NormalizedURICxRepo.getByNormalizedUrl(urlObj.url) match {
              case Some(u) => (u,URLHistoryCause.MERGE)
              case None => (NormalizedURIFactory(urlObj.url),URLHistoryCause.SPLIT)
            }
            if (currNormURI.url != renormURI.url) {
              changedURIs += ChangedNormURI("follow-nuri", urlObj.url, currNormURI.url, currNormURI.id.map(_.id).getOrElse(0L), renormURI.id.map(_.id).getOrElse(0L), renormURI.url)
              try {
                if(!readOnly) {
                  val savedNormURI = renormURI.save
                  urlObj.withHistory(URLHistory(savedNormURI.id.get,reason)).save
                  follow.withNormUriId(renormURI.id.get).saveWithCx
                }
              } catch {
                case ex: Throwable =>
                  log.error("URL Migration error",ex)
              }
            }
        }
      }


      val deeps = DeepLinkCxRepo.all
      val deepsCount = deeps.size

      deeps map { deep =>
        val currNormURI = NormalizedURICxRepo.get(deep.uriId.get)
        val urlObj = deep.urlId match {
          case Some(uu) => URLCxRepo.get(uu)
          case None =>
            val u = URL(url = currNormURI.url, normalizedUriId = currNormURI.id.get)
            if (!readOnly) u.save
            else u
        }
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") => Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            val (renormURI,reason) = NormalizedURICxRepo.getByNormalizedUrl(urlObj.url) match {
              case Some(u) => (u,URLHistoryCause.MERGE)
              case None => (NormalizedURIFactory(urlObj.url),URLHistoryCause.SPLIT)
            }
            if (currNormURI.url != renormURI.url) {
              changedURIs += ChangedNormURI("comment-nuri", urlObj.url, currNormURI.url, currNormURI.id.map(_.id).getOrElse(0L), renormURI.id.map(_.id).getOrElse(0L), renormURI.url)
              try {
                if(!readOnly) {
                  val savedNormURI = renormURI.save
                  urlObj.withHistory(URLHistory(savedNormURI.id.get,reason)).save
                  deep.withNormUriId(renormURI.id.get).save
                }
              } catch {
                case ex: Throwable =>
                  log.error("URL Migration error",ex)
              }
            }
        }
      }

      (bookmarkCount, commentCount, followsCount, deepsCount)
    }
    val out = changedURIs.map { u =>
      "%s, %s, %s, %s, %s, %s".format(u.context, u.url, u.currURI, u.from, u.to, u.toUri)
    }
    val header = "%s bookmarks, %s comments, %s follows, %s deeplinks processed. Found %s necessary updates.".format(bookmarkCount, commentCount, followsCount, deepsCount, out.size)
    header + "\n\n\n" + out.mkString("\n")
  }

}
