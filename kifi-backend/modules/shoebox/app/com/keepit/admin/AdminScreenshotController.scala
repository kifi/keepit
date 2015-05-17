package com.keepit.controllers.admin

import com.keepit.common.db.Id
import com.keepit.common.store.S3ImageConfig
import com.keepit.model._
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.rover.RoverServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import play.api.data._
import play.api.data.Forms._
import views.html
import com.keepit.commanders.{ ProcessedImageSize }
import com.keepit.normalizer.NormalizedURIInterner

class AdminScreenshotController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    keepRepo: KeepRepo,
    imageInfoRepo: ImageInfoRepo,
    normalizedURIInterner: NormalizedURIInterner,
    uriRepo: NormalizedURIRepo,
    rover: RoverServiceClient,
    implicit val imageConfig: S3ImageConfig) extends AdminUserActions {

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
          (uri, None)
        }
        rover.getImagesByUris(Set(uriId)).map { imagesByUriId =>
          val imageUrlOpt = imagesByUriId.get(uriId).flatMap(_.get(ProcessedImageSize.Large.idealSize).map(_.path.getUrl))
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
