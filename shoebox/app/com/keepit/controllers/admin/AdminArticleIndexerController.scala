package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.AdminController
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{JsNumber, JsObject}
import scala.concurrent.ExecutionContext.Implicits.global

class AdminArticleIndexerController @Inject()(
    searchClient: SearchServiceClient,
    db: Database,
    normUriRepo: NormalizedURIRepo
  ) extends AdminController {

  def index = AdminHtmlAction { implicit request =>
    Async {
      searchClient.index().map { cnt =>
        Ok("indexed %d articles".format(cnt))
      }
    }
  }

  def indexByState(state: State[NormalizedURI]) = AdminHtmlAction { implicit request =>
    transitionByAdmin(state -> Set(SCRAPED, SCRAPE_FAILED)) { newState =>
      db.readWrite { implicit s =>
        normUriRepo.getByState(state).foreach{ uri => normUriRepo.save(uri.withState(newState)) }
      }
      Async {
        searchClient.index().map { cnt =>
          Ok("indexed %d articles".format(cnt))
        }
      }
    }
  }

  def indexInfo = AdminHtmlAction { implicit request =>
    Async {
      (searchClient.articleIndexInfo() zip searchClient.uriGraphIndexInfo()).map { case (aiInfo, ugInfo) =>
        Ok(views.html.admin.indexer(aiInfo, ugInfo))
      }
    }
  }

  def getSequenceNumber = AdminJsonAction { implicit request =>
    Async {
      searchClient.articleIndexerSequenceNumber().map { number =>
        Ok(JsObject(Seq("sequenceNumber" -> JsNumber(number))))
      }
    }
  }

  def refreshSearcher = AdminHtmlAction { implicit request =>
    Async {
      searchClient.refreshSearcher().map { _ =>
        Ok("searcher refreshed")
      }
    }
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]) =  AdminHtmlAction { implicit request =>
    Async {
      searchClient.dumpLuceneDocument(id).map(Ok(_))
    }
  }
}

