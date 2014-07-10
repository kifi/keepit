package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.common.db.slick.Database
import com.keepit.abook.ABookServiceClient
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.time._
import com.keepit.model.ABookInfo
import com.keepit.common.db.Id
import org.joda.time.DateTime
import scala.concurrent.Future

class ABookAdminController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  abookServiceClient: ABookServiceClient)
    extends AdminController(actionAuthenticator) {

  def allABooksView = abooksView(0)

  def abooksView(page: Int) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val PAGE_SIZE = 50
    val abookInfosFuture: Future[Seq[ABookInfo]] = abookServiceClient.getPagedABookInfos(page, PAGE_SIZE)
    val abooksCountFuture: Future[Int] = abookServiceClient.getABooksCount()
    for {
      abookInfos <- abookInfosFuture
      abooksCount <- abooksCountFuture
    } yield Ok(views.html.admin.abook(abookInfos, page, abooksCount, PAGE_SIZE))
  }

}

