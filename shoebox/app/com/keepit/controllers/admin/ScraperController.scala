package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import play.api._
import libs.concurrent.Akka
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.common.db._

import com.keepit.scraper._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.ArticleStore
import javax.xml.bind.DatatypeConverter._
import com.keepit.common.mail._
import slick.Database
import com.keepit.scraper.DuplicateDocumentDetection
import views.html

import com.keepit.common.controller.AdminController
import com.google.inject.{Inject, Singleton, Provider}

case class DisplayedDuplicate(id: Id[DuplicateDocument], normUriId: Id[NormalizedURI], url: String, percentMatch: Double)
case class DisplayedDuplicates(normUriId: Id[NormalizedURI], url: String, dupes: Seq[DisplayedDuplicate])

@Singleton
class ScraperController @Inject() (
  db: Database,
  scraper: ScraperPlugin,
  scrapeInfoRepo: ScrapeInfoRepo,
  normalizedURIRepo: NormalizedURIRepo,
  articleStore: ArticleStore,
  unscrapableRepo: UnscrapableRepo,
  duplicateDocumentRepo: DuplicateDocumentRepo,
  followRepo: FollowRepo,
  deeplinkRepo: DeepLinkRepo,
  commentRepo: CommentRepo,
  bookmarkRepo: BookmarkRepo)
    extends AdminController {

  def scrape = AdminHtmlAction { implicit request =>
    val articles = scraper.scrape()
    Ok(html.admin.scrape(articles))
  }

  def scrapeByState(state: State[NormalizedURI]) = AdminHtmlAction { implicit request =>
    transitionByAdmin(state -> Set(ACTIVE)) { newState =>
      db.readWrite { implicit s =>
        normalizedURIRepo.getByState(state).foreach{ uri => normalizedURIRepo.save(uri.withState(newState)) }
      }
      val articles = scraper.scrape()
      Ok(html.admin.scrape(articles))
    }
  }

  def getScraped(id: Id[NormalizedURI]) = AdminHtmlAction { implicit request =>
    val article = articleStore.get(id).get
    val (uri, info) = db.readOnly { implicit s =>
      (normalizedURIRepo.get(article.id), scrapeInfoRepo.getByUri(article.id))
    }

    Ok(html.admin.article(article, uri, info.get))
  }

  def getUnscrapable() = AdminHtmlAction { implicit request =>
    val docs = db.readOnly { implicit conn =>
      unscrapableRepo.allActive()
    }

    Ok(html.admin.unscrapable(docs))
  }

  def previewUnscrapable() = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("No form data given.")
    }
    val pattern = form.get("pattern").get
    val numRecords = form.get("count").get.toInt
    val records = {
      val (destinSet, normSet) = db.readWrite { implicit conn =>
        val paged = scrapeInfoRepo.page(0, numRecords)
        (paged.map(si => si.destinationUrl).flatten, paged.map(si => normalizedURIRepo.get(si.uriId).url))
      }
      destinSet.filter(_.matches(pattern)) ++ normSet.filter(_.matches(pattern))
    }
    Ok(html.admin.unscrapablePreview(pattern, numRecords, records))
  }

  def createUnscrapable() = AdminHtmlAction { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("No form data given.")
    }
    val pattern = form.get("pattern").get
    db.readWrite { implicit conn =>
      unscrapableRepo.save(Unscrapable(pattern = pattern))
    }
    Redirect(com.keepit.controllers.admin.routes.ScraperController.getUnscrapable())
  }

  def orphanCleanup() = AdminHtmlAction { implicit request =>
    val orphanCleaner = new OrphanCleaner
    Akka.future {
      db.readWrite { implicit session =>
        orphanCleaner.cleanNormalizedURIs(false)
        orphanCleaner.cleanScrapeInfo(false)
      }
    }
    Redirect(com.keepit.controllers.admin.routes.ScraperController.documentIntegrity())
  }

  def documentIntegrity(page: Int = 0, size: Int = 50) = AdminHtmlAction { implicit request =>
    val dupes = db.readOnly { implicit conn =>
      duplicateDocumentRepo.getActive(page, size)
    }

    val groupedDupes = dupes.groupBy { case d => d.uri1Id }.toSeq.sortWith((a,b) => a._1.id < b._1.id)

    val loadedDupes = db.readOnly { implicit session =>
      groupedDupes map  { d =>
        val dupeRecords = d._2.map { sd =>
          DisplayedDuplicate(sd.id.get, sd.uri2Id, normalizedURIRepo.get(sd.uri2Id).url, sd.percentMatch)
        }
        DisplayedDuplicates(d._1, normalizedURIRepo.get(d._1).url, dupeRecords)
      }
    }

    Ok(html.admin.documentIntegrity(loadedDupes))
  }

  def typedAction(action: String) = action match {
    case "ignore" => DuplicateDocumentStates.IGNORED
    case "merge" => DuplicateDocumentStates.MERGED
    case "unscrapable" => DuplicateDocumentStates.UNSCRAPABLE
  }

  def handleDuplicate = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = typedAction(body("action").head)
    val id = Id[DuplicateDocument](body("id").head.toLong)

    action match {
      case DuplicateDocumentStates.MERGED =>
        db.readOnly { implicit session =>
          val d = duplicateDocumentRepo.get(id)
          mergeUris(d.uri1Id, d.uri2Id)
        }
      case _ =>
    }

    db.readWrite { implicit session =>
      duplicateDocumentRepo.save(duplicateDocumentRepo.get(id).withState(action))
    }
    Ok
  }

  def mergeUris(parentId: Id[NormalizedURI], childId: Id[NormalizedURI]) = {
    // Collect all entities who refer to N2 and change the ref to N1.
    // Update the URL db entity with state: manual fix
    db.readWrite { implicit session =>
      // Bookmark
      bookmarkRepo.getByUri(childId).map { bookmark =>
        bookmarkRepo.save(bookmark.withNormUriId(parentId))
      }
      // Comment
      commentRepo.getByUri(childId).map { comment =>
        commentRepo.save(comment.withNormUriId(parentId))
      }

      // DeepLink
      deeplinkRepo.getByUri(childId).map { deeplink =>
        deeplinkRepo.save(deeplink.withNormUriId(parentId))
      }

      // Follow
      followRepo.getByUri(childId, excludeState = None).map { follow =>
        followRepo.save(follow.withNormUriId(parentId))
      }
    }
  }

  def handleDuplicates = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val action = body("action").head
    val id = Id[NormalizedURI](body("id").head.toLong)
    db.readWrite { implicit session =>
      duplicateDocumentRepo.getSimilarTo(id) map { dupe =>
        action match {
          case DuplicateDocumentStates.MERGED =>
              mergeUris(dupe.uri1Id, dupe.uri2Id)
          case _ =>
        }
        duplicateDocumentRepo.save(dupe.withState(typedAction(action)))
      }
    }
    Ok
  }

  def duplicateDocumentDetection = AdminHtmlAction { implicit request =>
    val dupeDetect = new DuplicateDocumentDetection
    dupeDetect.asyncProcessDocuments()

    Redirect(com.keepit.controllers.admin.routes.ScraperController.documentIntegrity())
  }
}

