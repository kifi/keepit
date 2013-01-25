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
      val result = doRenormalize(readOnly, domain).replaceAll("\n","\n<br>")
      val postOffice = inject[PostOffice]
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = "Renormalization Report", htmlBody = result, category = PostOffice.Categories.ADMIN))
    }
    Ok("Started! Will email %s".format(EmailAddresses.ENG))
  }

  def doRenormalize(readOnly: Boolean = true, domain: Option[String] = None) = {
    // Processes all models that reference a `NormalizedURI`, and renormalizes all URLs.
    val changedURIs = scala.collection.mutable.MutableList[ChangedNormURI]()
    val (urlsSize, changes) = CX.withConnection { implicit conn =>

      val urls = URLCxRepo.all
      val urlsSize = urls.size
      val changes = scala.collection.mutable.Map[String, Int]()

      urls map { url =>
        url.state match {
          case URLStates.ACTIVE =>
            val (normalizedUri, reason) = NormalizedURICxRepo.getByNormalizedUrl(url.url) match {
              case Some(nuri) =>
                (nuri,URLHistoryCause.MERGE)
              case None =>
                // No normalized URI exists for this url, create one
                val nuri = NormalizedURIFactory.apply(url.url)
                ({if(!readOnly)
                  nuri.save
                else
                  nuri}, URLHistoryCause.SPLIT)
            }

            changes += (("url", 0))
            if(url.normalizedUriId != normalizedUri.id.get.id) {
              changes("url") += 1
              if(!readOnly) {
                url.withNormUriId(normalizedUri.id.get).withHistory(URLHistory(normalizedUri.id.get,reason)).save
              }
            }

            changes += (("bookmark", 0))
            BookmarkCxRepo.getByUrlId(url.id.get) map { s =>
              if(s.uriId.id != normalizedUri.id.get.id) {
                changes("bookmark") += 1
                if(!readOnly) {
                  s.withNormUriId(normalizedUri.id.get).save
                }
              }
            }

            changes += (("comment", 0))
            CommentCxRepo.getByUrlId(url.id.get) map { s =>
              if(s.uriId.id != normalizedUri.id.get.id) {
                changes("comment") += 1
                if(!readOnly) {
                  s.withNormUriId(normalizedUri.id.get).save
                }
              }
            }

            changes += (("deeplink", 0))
            DeepLinkCxRepo.getByUrlId(url.id.get) map { s =>
              if(s.uriId.get.id != normalizedUri.id.get.id) {
                changes("deeplink") += 1
                if(!readOnly) {
                  s.withNormUriId(normalizedUri.id.get).save
                }
              }
            }

            changes += (("follow", 0))
            FollowCxRepo.getByUrlId(url.id.get) map { s =>
              if(s.uriId.id != normalizedUri.id.get.id) {
                changes("follow") += 1
                if(!readOnly) {
                  s.withNormUriId(normalizedUri.id.get).save
                }
              }
            }

          case _ => // ignore
        }
      }

      (urlsSize, changes)
    }

    "%s urls processed, %s changes.<br>\n<br>\n%s".format(urlsSize, changes.size, changes)
  }

}
