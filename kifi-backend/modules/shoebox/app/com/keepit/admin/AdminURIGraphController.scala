package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model.{BookmarkRepo, CollectionRepo, Collection, User}
import com.keepit.search.SearchServiceClient
import scala.concurrent.ExecutionContext.Implicits.global

class AdminURIGraphController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  bookmarkRepo: BookmarkRepo,
  collectionRepo: CollectionRepo,
  searchClient: SearchServiceClient)
    extends AdminController(actionAuthenticator) {

  def load = AdminHtmlAction { implicit request =>
    Async {
      searchClient.updateURIGraph().map { cnt =>
        Ok(s"indexed $cnt users")
      }
    }
  }

  def update(userId: Id[User]) = AdminHtmlAction { implicit request =>
    Async {
      val bookmarks = db.readOnly { implicit s => bookmarkRepo.getByUser(userId) }
      bookmarks.grouped(1000).foreach { group =>
        db.readWrite { implicit s => group.foreach(bookmarkRepo.save) }
      }
      searchClient.updateURIGraph().map { cnt =>
        Ok(s"indexed $cnt users")
      }
    }
  }

  def reindex = AdminHtmlAction { implicit request =>
    Async {
      searchClient.reindexURIGraph().map { cnt =>
        Ok("reindexing started")
      }
    }
  }

  def dumpLuceneDocument(id: Id[User]) =  AdminHtmlAction { implicit request =>
    Async {
      searchClient.dumpLuceneURIGraph(id).map(Ok(_))
    }
  }

  def reindexCollection = AdminHtmlAction { implicit request =>
    Async {
      searchClient.reindexCollection().map { cnt =>
        Ok("reindexing started")
      }
    }
  }

  def dumpCollectionLuceneDocument(id: Id[Collection]) =  AdminHtmlAction { implicit request =>
    Async {
      val collection = db.readOnly { implicit s => collectionRepo.get(id) }
      searchClient.dumpLuceneCollection(id, collection.userId).map(Ok(_))
    }
  }
}

