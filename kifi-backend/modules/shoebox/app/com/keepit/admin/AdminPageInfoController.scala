package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.libs.json.Json
import views.html

class AdminPageInfoController @Inject() (val userActionsHelper: UserActionsHelper,
  db: Database,
  keepRepo: KeepRepo,
  pageInfoRepo: PageInfoRepo,
  uriRepo: NormalizedURIRepo)
    extends AdminUserActions {

  def pageInfo(id: Id[PageInfo]) = AdminUserPage { request =>
    val pageInfo = db.readOnlyReplica { implicit ro =>
      pageInfoRepo.get(id)
    }
    Ok(html.admin.pageInfo(pageInfo))
  }

  def pageInfos(page: Int, size: Int) = AdminUserPage { request =>
    val pageInfos = db.readOnlyReplica { implicit ro =>
      pageInfoRepo.page(page, size).sortBy(_.id.get.id)
    }
    // add pagination
    Ok(html.admin.pageInfos(pageInfos))
  }

}
