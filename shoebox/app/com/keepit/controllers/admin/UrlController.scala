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

  case class ChangedURL(context: String, oldUrl: String, from: Option[Id[URL]], to: Id[URL])
  case class ChangedNormURI(context: String, url: String, from: Option[Id[NormalizedURI]], to: Id[NormalizedURI], toUrl: String)

  def grandfathering(readOnly: Boolean = true, domain: Option[String] = None, page: Int = -1) = AdminHtmlAction { implicit request =>
    Akka.future {
      val result = doGrandfathering(readOnly, domain).replaceAll("\n","\n<br>");
      val postOffice = inject[PostOffice]
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = "Grandfathering Report", htmlBody = result, category = PostOffice.Categories.ADMIN))
    }
    Ok("Started! Will email %s".format(EmailAddresses.ENG))
  }

  def doGrandfathering(readOnly: Boolean, domain: Option[String] = None, page: Int = -1) = {
    // Creates urlId connections where they do not exist, and verifies that references to URL are correct
    // After migration, these models should use `urlId` appropriately. Any nulls should give an indication
    // of controllers not setting the `urlId`.

    val changedURLs = scala.collection.mutable.MutableList[ChangedURL]()
    val (bookmarkCount, commentCount, followsCount, deepsCount) = CX.withConnection { implicit conn =>

      val bookmarks = page match { case i: Int if i >= 0 => Bookmark.page(i,20) case _ => Bookmark.all }
      val bookmarkCount = bookmarks.size

      bookmarks map { bookmark =>
        val urlObj = URL.get(bookmark.url).getOrElse{
          val u = URL(bookmark.url, bookmark.uriId)
          if(!readOnly) u.save
          else u
        }
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") =>
            Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            bookmark.urlId match {
              case Some(id) if id.id == urlObj.id.map(_.id).getOrElse(0) =>
              case _ =>
                changedURLs += ChangedURL("bookmark-url", bookmark.url, bookmark.urlId, urlObj.id.getOrElse(Id[URL](0)))
                if(!readOnly) bookmark.withUrlId(urlObj.id.get).save
            }
        }

      }

      val comments = page match { case i: Int if i > 0 => Comment.page(i,20) case _ => Comment.all }
      val commentCount = comments.size

      comments map { comment =>
        val normUri = NormalizedURI.get(comment.uriId)
        val urlObj = URL.get(normUri.url).getOrElse {
          val u = URL(normUri.url, normUri.id.get)
          if(!readOnly) u.save
          else u
        }

        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") =>
            Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            comment.urlId match {
              case Some(id) if id.id == urlObj.id.map(_.id).getOrElse(0) =>
              case None =>
                changedURLs += ChangedURL("comment-url", normUri.url, comment.urlId, urlObj.id.getOrElse(Id[URL](0)) )
                if(!readOnly) comment.withUrlId(urlObj.id.get).save
            }
        }
      }

      val follows = FollowCxRepo.all
      val followsCount = follows.size

      follows map { follow =>
        val normUri = NormalizedURI.get(follow.uriId)
        val urlObj = URL.get(normUri.url).getOrElse {
          val u = URL(normUri.url, normUri.id.get)
          if(!readOnly) u.save
          else u
        }
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") =>
            Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            follow.urlId match {
              case Some(id) if id.id == urlObj.id.map(_.id).getOrElse(0) =>
              case None =>
                changedURLs += ChangedURL("follow-url", normUri.url, follow.urlId, urlObj.id.getOrElse(Id[URL](0)) )
                if(!readOnly) follow.withUrlId(urlObj.id.get).saveWithCx
            }
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
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") =>
            Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            deeps.urlId match {
              case Some(id) if id.id == urlObj.id.map(_.id).getOrElse(0) =>
              case None =>
                changedURLs += ChangedURL("deep-url", normUri.url, deeps.urlId, urlObj.id.getOrElse(Id[URL](0)) )
                if(!readOnly) deeps.withUrlId(urlObj.id.get).save
            }
        }
      }

      (bookmarkCount, commentCount, followsCount, deepsCount)
    }
    val out = changedURLs.map { u =>
      "%s, %s, %s, %s".format(u.context,u.oldUrl, u.from.map(s=>s.id.toString).getOrElse("NULL"), u.to.id)
    }
    val header = "%s bookmarks, %s comments, %s follows, %s deeplinks processed. Found %s necessary updates.".format(bookmarkCount, commentCount, followsCount, deepsCount, out.size)
    header + "\n\n\n" + out.mkString("\n")
  }

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
    // Should be run AFTER grandfathering.
    val changedURIs = scala.collection.mutable.MutableList[ChangedNormURI]()
    val (bookmarkCount, commentCount, followsCount, deepsCount) = CX.withConnection { implicit conn =>

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
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") => Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            val (renormURI,reason) = NormalizedURI.getByNormalizedUrl(urlObj.url) match {
              case Some(u) => (u,URLHistoryCause.MERGE)
              case None => (NormalizedURI(urlObj.url).save,URLHistoryCause.SPLIT)
            }

            if (currNormURI.id != renormURI.id) {
              changedURIs += ChangedNormURI("bookmark-nuri", urlObj.url, currNormURI.id, renormURI.id.get, renormURI.url)
              if(!readOnly) {
                urlObj.withHistory(URLHistory(renormURI.id.get,reason)).save
                bookmark.withNormUriId(renormURI.id.get).save
              }
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
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") => Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            val (renormURI,reason) = NormalizedURI.getByNormalizedUrl(urlObj.url) match {
              case Some(u) => (u,URLHistoryCause.MERGE)
              case None => (NormalizedURI(urlObj.url).save,URLHistoryCause.SPLIT)
            }
            if (currNormURI.id != renormURI.id) {
              changedURIs += ChangedNormURI("comment-nuri", urlObj.url, currNormURI.id, renormURI.id.get, renormURI.url)
              if(!readOnly) {
                urlObj.withHistory(URLHistory(renormURI.id.get,reason)).save
                comment.withNormUriId(renormURI.id.get).save
              }
            }
        }
      }

      val follows = FollowCxRepo.all
      val followsCount = follows.size

      follows map { follow =>
        val currNormURI = NormalizedURI.get(follow.uriId)
        val urlObj = follow.urlId match {
          case Some(uu) => URL.get(uu)
          case None =>
            val u = URL(currNormURI.url, currNormURI.id.get)
            if (!readOnly) u.save
            else u
        }
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") => Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            val (renormURI,reason) = NormalizedURI.getByNormalizedUrl(urlObj.url) match {
              case Some(u) => (u,URLHistoryCause.MERGE)
              case None => (NormalizedURI(urlObj.url).save,URLHistoryCause.SPLIT)
            }
            if (currNormURI.id != renormURI.id) {
              changedURIs += ChangedNormURI("follow-nuri", urlObj.url, currNormURI.id, renormURI.id.get, renormURI.url)
              if(!readOnly) {
                urlObj.withHistory(URLHistory(renormURI.id.get,reason)).save
                follow.withNormUriId(renormURI.id.get).saveWithCx
              }
            }
        }
      }


      val deeps = DeepLink.all
      val deepsCount = deeps.size

      deeps map { deep =>
        val currNormURI = NormalizedURI.get(deep.uriId.get)
        val urlObj = deep.urlId match {
          case Some(uu) => URL.get(uu)
          case None =>
            val u = URL(currNormURI.url, currNormURI.id.get)
            if (!readOnly) u.save
            else u
        }
        (domain match {
          case Some(d) if urlObj.domain.map(_.toString).getOrElse("") != domain.getOrElse("") => Some(d)
          case _ => None
        }) match {
          case Some(d) =>
          case None =>
            val (renormURI,reason) = NormalizedURI.getByNormalizedUrl(urlObj.url) match {
              case Some(u) => (u,URLHistoryCause.MERGE)
              case None => (NormalizedURI(urlObj.url).save,URLHistoryCause.SPLIT)
            }
            if (currNormURI.id != renormURI.id) {
              changedURIs += ChangedNormURI("comment-nuri", urlObj.url, currNormURI.id, renormURI.id.get, renormURI.url)
              if(!readOnly) {
                urlObj.withHistory(URLHistory(renormURI.id.get,reason)).save
                deep.withNormUriId(renormURI.id.get).save
              }
            }
        }
      }

      (bookmarkCount, commentCount, followsCount, deepsCount)
    }
    val out = changedURIs.map { u =>
      "%s, %s, %s, %s".format(u.context,u.url, u.from.map(s=>s.id.toString).getOrElse("NULL"), u.to.id)
    }
    val header = "%s bookmarks, %s comments, %s follows, %s deeplinks processed. Found %s necessary updates.".format(bookmarkCount, commentCount, followsCount, deepsCount, out.size)
    header + "\n\n\n" + out.mkString("\n")
  }

}
