package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.slick.Database
import com.keepit.abook.ABookServiceClient
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.time._
import com.keepit.model.ABookInfo
import com.keepit.common.db.Id
import org.joda.time.DateTime
import scala.concurrent.Future

class ABookAdminController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  abookServiceClient: ABookServiceClient)
    extends AdminUserActions {

  def allABooksView = abooksView(0)

  def abooksView(page: Int) = AdminUserPage.async { implicit request =>
    val PAGE_SIZE = 50
    val abookInfosFuture: Future[Seq[ABookInfo]] = abookServiceClient.getPagedABookInfos(page, PAGE_SIZE)
    val abooksCountFuture: Future[Int] = abookServiceClient.getABooksCount()
    for {
      abookInfos <- abookInfosFuture
      abooksCount <- abooksCountFuture
    } yield Ok(views.html.admin.abook(abookInfos, page, abooksCount, PAGE_SIZE))
  }

}

