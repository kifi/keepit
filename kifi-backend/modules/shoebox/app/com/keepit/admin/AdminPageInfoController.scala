package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.libs.json.Json
import views.html

class AdminPageInfoController @Inject() (actionAuthenticator: ActionAuthenticator,
  db: Database,
  keepRepo: KeepRepo,
  pageInfoRepo: PageInfoRepo,
  uriRepo: NormalizedURIRepo)
    extends AdminController(actionAuthenticator) {

  def pageInfo(id: Id[PageInfo]) = AdminHtmlAction.authenticated { request =>
    val pageInfo = db.readOnlyReplica { implicit ro =>
      pageInfoRepo.get(id)
    }
    Ok(html.admin.pageInfo(pageInfo))
  }

  def pageInfos(page: Int, size: Int) = AdminHtmlAction.authenticated { request =>
    val pageInfos = db.readOnlyReplica { implicit ro =>
      pageInfoRepo.page(page, size).sortBy(_.id.get.id)
    }
    // add pagination
    Ok(html.admin.pageInfos(pageInfos))
  }

}
