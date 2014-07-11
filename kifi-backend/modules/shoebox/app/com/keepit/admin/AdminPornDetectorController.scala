package com.keepit.controllers.admin

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIRepo
import com.keepit.model.NormalizedURIRepoImpl
import com.keepit.scraper.ScraperServiceClient
import views.html
import com.keepit.model.Restriction
import com.keepit.model.KeepRepo
import scala.collection.mutable.ArrayBuffer

class AdminPornDetectorController @Inject() (
    scraper: ScraperServiceClient,
    db: Database,
    uriRepo: NormalizedURIRepo,
    bmRepo: KeepRepo,
    actionAuthenticator: ActionAuthenticator) extends AdminController(actionAuthenticator) {

  private def tokenize(query: String): Array[String] = {
    query.split("[^a-zA-Z0-9]").filter(!_.isEmpty).map { _.toLowerCase }
  }

  def index() = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.pornDetector())
  }

  def detect() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val text = body.get("query").get
    val numBlocks = tokenize(text).sliding(10, 10).size
    val badTexts = Await.result(scraper.detectPorn(text), 15 seconds)
    val badInfo = badTexts.map { x => x._1 + " ---> " + x._2 }.mkString("\n")
    val msg = if (badTexts.size == 0) "input text is clean" else s"${badTexts.size} out of ${numBlocks} blocks look suspicious:\n" + badInfo
    Ok(msg.replaceAll("\n", "\n<br>"))
  }

  def pornUrisView(page: Int, publicOnly: Boolean) = AdminHtmlAction.authenticated { implicit request =>
    val uris = db.readonlyReplica { implicit s => uriRepo.getRestrictedURIs(Restriction.ADULT) }.sortBy(-_.updatedAt.getMillis())
    val PAGE_SIZE = 100

    val retUris = publicOnly match {
      case false => uris.toArray
      case true => {
        val need = (page + 1) * PAGE_SIZE
        val buf = new ArrayBuffer[NormalizedURI]()
        var (i, cnt) = (0, 0)
        db.readonlyReplica { implicit s =>
          while (i < uris.size && cnt < need) {
            val bms = bmRepo.getByUri(uris(i).id.get)
            if (bms.exists(_.isPrivate == false)) {
              buf.append(uris(i))
              cnt += 1
            }
            i += 1
          }
        }
        buf.toArray
      }
    }

    val pageCount = (retUris.size * 1.0 / PAGE_SIZE).ceil.toInt

    Ok(html.admin.pornUris(retUris.drop(page * PAGE_SIZE).take(PAGE_SIZE), retUris.size, page, pageCount, publicOnly))
  }

  def removeRestrictions() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val ids = body.get("uriIds").get.split(",").map { id => Id[NormalizedURI](id.toLong) }
    db.readWrite { implicit s =>
      ids.foreach { id =>
        val uri = uriRepo.get(id)
        if (uri.restriction == Some(Restriction.ADULT)) uriRepo.save(uri.copy(restriction = None))
      }
    }
    Ok(s"${ids.size} uris' adult restriction removed")
  }

  def whitelist() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val whitelist = body.get("whitelist").get
    val cleaned = Await.result(scraper.whitelist(whitelist), 5 seconds)
    Ok(s"following words are cleaned: " + cleaned)
  }
}
