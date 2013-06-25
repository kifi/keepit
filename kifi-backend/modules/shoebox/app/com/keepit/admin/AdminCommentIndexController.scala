package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model.Comment
import com.keepit.search.SearchServiceClient
import scala.concurrent.ExecutionContext.Implicits.global

class AdminCommentIndexController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  searchClient: SearchServiceClient)
    extends AdminController(actionAuthenticator) {

  def reindex = AdminHtmlAction { implicit request =>
    Async {
      searchClient.reindexComment().map { cnt =>
        Ok("reindexing started")
      }
    }
  }

  def dumpLuceneDocument(id: Id[Comment]) =  AdminHtmlAction { implicit request =>
    Async {
      searchClient.dumpLuceneComment(id).map(Ok(_))
    }
  }
}

