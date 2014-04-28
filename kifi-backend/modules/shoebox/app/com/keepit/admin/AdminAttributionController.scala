package com.keepit.controllers.admin

import com.keepit.common.db.Id
import com.keepit.model._
import views.html
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import play.api.data._
import views.html

class AdminAttributionController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  keepRepo: KeepRepo,
  keepClickRepo: KeepClickRepo,
  rekeepRepo: ReKeepRepo,
  uriRepo: NormalizedURIRepo,
  pageInfoRepo: PageInfoRepo,
  imageInfoRepo: ImageInfoRepo
) extends AdminController(actionAuthenticator) {

  def keepClicks(page:Int, size:Int, showImage:Boolean) = AdminHtmlAction.authenticated { request =>
    val t = db.readOnly { implicit ro =>
      keepClickRepo.page(page, size).map { c =>
        val uri = uriRepo.get(c.uriId)
        val pageInfoOpt = pageInfoRepo.getByUri(c.uriId)
        val imgOpt = if (!showImage) None else
          for {
            pageInfo <- pageInfoOpt
            imgId <- pageInfo.imageInfoId
          } yield imageInfoRepo.get(imgId)
        (c, uri, pageInfoOpt, imgOpt)
      }
    }
    Ok(html.admin.keepClicks(t, showImage))
  }


  def rekeeps(page:Int, size:Int, showImage:Boolean) = AdminHtmlAction.authenticated { request =>
    val t = db.readOnly { implicit ro =>
      rekeepRepo.page(page, size).map { k =>
        val uri = uriRepo.get(k.uriId)
        val pageInfoOpt = pageInfoRepo.getByUri(k.uriId)
        val imgOpt = if (!showImage) None else
          for {
            pageInfo <- pageInfoOpt
            imgId <- pageInfo.imageInfoId
          } yield imageInfoRepo.get(imgId)
        (k, uri, pageInfoOpt, imgOpt)
      }
    }
    Ok(html.admin.rekeeps(t, showImage))
  }

}
