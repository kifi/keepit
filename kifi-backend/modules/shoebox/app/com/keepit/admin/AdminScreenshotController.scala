package com.keepit.controllers.admin

import com.keepit.common.db.Id
import com.keepit.common.db.LargeString._
import com.keepit.model._
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import play.api.libs.json.{ JsValue, JsArray, Json }
import play.api.data._
import play.api.data.Forms._
import views.html
import scala.util.{ Failure, Success, Try }
import com.keepit.commanders.URISummaryCommander
import com.keepit.scraper.ScraperServiceClient
import com.keepit.normalizer.NormalizedURIInterner

class AdminScreenshotController @Inject() (
  val userActionsHelper: UserActionsHelper,
  uriSummaryCommander: URISummaryCommander,
  uriImageCommander: URISummaryCommander,
  scraper: ScraperServiceClient,
  db: Database,
  keepRepo: KeepRepo,
  pageInfoRepo: PageInfoRepo,
  imageInfoRepo: ImageInfoRepo,
  normalizedURIInterner: NormalizedURIInterner,
  uriRepo: NormalizedURIRepo)
    extends AdminUserActions {

  def images() = AdminUserPage { implicit request =>
    Ok(html.admin.images())
  }

  def imageInfos() = AdminUserPage { implicit request =>
    val imageInfos = db.readOnlyMaster { implicit ro =>
      imageInfoRepo.page(page = 0, size = 50).sortBy(_.id.get.id)
    }
    // add pagination
    Ok(html.admin.imageInfos(imageInfos))
  }

  val compareForm = Form("uriIds" -> text)
  def imagesCompare() = AdminUserPage.async { implicit request =>
    try {
      val uriIds = compareForm.bindFromRequest.get.split(',').map(s => Id[NormalizedURI](s.toLong)).toSeq
      val tuplesF = uriIds map { uriId =>
        val (uri, pageInfoOpt) = db.readOnlyMaster { implicit ro =>
          val uri = uriRepo.get(uriId)
          val pageInfoOpt = pageInfoRepo.getByUri(uriId)
          (uri, pageInfoOpt)
        }
        uriSummaryCommander.getURIImage(uri) map { imageUrlOpt =>
          (uri, None, imageUrlOpt)
        }
      }
      Future.sequence(tuplesF) map { tuples =>
        Ok(html.admin.imagesCompare(tuples))
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace
        Future.successful(BadRequest("Invalid Arguments"))
    }
  }

}
