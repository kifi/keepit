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

import play.api.libs.concurrent.Execution.Implicits._
import java.util.concurrent.TimeUnit

import com.keepit.common.db._
import com.keepit.common.db.slick._

import com.keepit.model._
import com.keepit.search.ArticleStore
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.time._
import com.keepit.common.healthcheck.BabysitterTimeout
import org.joda.time.LocalDate
import org.joda.time.DateTimeZone
import com.keepit.common.net.URINormalizer
import com.keepit.common.mail._
import play.api.libs.concurrent.Akka
import scala.concurrent.duration._
import views.html

/**
 * Charts, etc.
 */
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton}

@Singleton
class UrlController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  clock: Clock,
  postOffice: PostOffice,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  userRepo: UserRepo,
  bookmarkRepo: BookmarkRepo,
  commentRepo: CommentRepo,
  deepLinkRepo: DeepLinkRepo,
  followRepo: FollowRepo)
    extends AdminController(actionAuthenticator) {

  implicit val timeout = BabysitterTimeout(5 minutes, 5 minutes)

  def index = AdminHtmlAction { implicit request =>
    Ok(html.admin.adminDashboard())
  }

  case class ChangedNormURI(context: String, url: String, currURI: String, from: Long, to: Long, toUri: String)

  def renormalize(readOnly: Boolean = true, domain: Option[String] = None) = AdminHtmlAction { implicit request =>
    Akka.future {
      try {
        val result = doRenormalize(readOnly, domain).replaceAll("\n","\n<br>")
        db.readWrite { implicit s =>
          postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
           subject = "Renormalization Report", htmlBody = result, category = PostOffice.Categories.ADMIN))
        }
      } catch {
        case ex: Throwable => log.error(ex.getMessage, ex)
      }
    }
    Ok("Started! Will email %s".format(EmailAddresses.ENG))
  }

  def doRenormalize(readOnly: Boolean = true, domain: Option[String] = None) = {
    // Processes all models that reference a `NormalizedURI`, and renormalizes all URLs.
    val changedURIs = scala.collection.mutable.MutableList[ChangedNormURI]()
    val (urlsSize, changes) = db.readWrite {implicit session =>

      val urls = domain match {
        case Some(domainStr) => urlRepo.getByDomain(domainStr)
        case None => urlRepo.all
      }

      val urlsSize = urls.size
      val changes = scala.collection.mutable.Map[String, Int]()
      changes += (("url", 0))
      changes += (("bookmark", 0))
      changes += (("comment", 0))
      changes += (("deeplink", 0))
      changes += (("follow", 0))

      urls map { url =>
        url.state match {
          case URLStates.ACTIVE =>
            val (normalizedUri, reason) = uriRepo.getByNormalizedUrl(url.url) match {
              case Some(nuri) =>
                (nuri, URLHistoryCause.MERGE)
              case None =>
                // No normalized URI exists for this url, create one
                val nuri = NormalizedURIFactory(url.url)
                ({if(!readOnly)
                  uriRepo.save(nuri)
                else
                  nuri}, URLHistoryCause.SPLIT)
            }

            if(normalizedUri.id.isEmpty || url.normalizedUriId.id != normalizedUri.id.get.id) {
              changes("url") += 1
              if(!readOnly) {
                urlRepo.save(url.withNormUriId(normalizedUri.id.get).withHistory(URLHistory(clock.now, normalizedUri.id.get,reason)))
              }
            }

            bookmarkRepo.getByUrlId(url.id.get) map { s =>
              if(normalizedUri.id.isEmpty || s.uriId.id != normalizedUri.id.get.id) {
                changes("bookmark") += 1
                if(!readOnly) {
                  bookmarkRepo.save(s.withNormUriId(normalizedUri.id.get))
                }
              }
            }

            commentRepo.getByUrlId(url.id.get) map { s =>
              if(normalizedUri.id.isEmpty || s.uriId.id != normalizedUri.id.get.id) {
                changes("comment") += 1
                if(!readOnly) {
                  commentRepo.save(s.withNormUriId(normalizedUri.id.get))
                }
              }
            }

            deepLinkRepo.getByUrl(url.id.get) map { s =>
              if(normalizedUri.id.isEmpty || s.uriId.get.id != normalizedUri.id.get.id) {
                changes("deeplink") += 1
                if(!readOnly) {
                  deepLinkRepo.save(s.withNormUriId(normalizedUri.id.get))
                }
              }
            }

            followRepo.getByUrl(url.id.get, excludeState = None) map { s =>
              if(normalizedUri.id.isEmpty || s.uriId.id != normalizedUri.id.get.id) {
                changes("follow") += 1
                if(!readOnly) {
                  followRepo.save(s.withNormUriId(normalizedUri.id.get))
                }
              }
            }

          case _ => // ignore
        }
      }
      (urlsSize, changes)
    }

    "%s urls processed, changes:<br>\n<br>\n%s".format(urlsSize, changes)
  }

}
