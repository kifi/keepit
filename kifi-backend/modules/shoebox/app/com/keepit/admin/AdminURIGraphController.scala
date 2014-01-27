package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model.{BookmarkRepo, CollectionRepo, Collection, User}
import com.keepit.search.SearchServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class AdminURIGraphController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  bookmarkRepo: BookmarkRepo,
  collectionRepo: CollectionRepo,
  searchClient: SearchServiceClient)
    extends AdminController(actionAuthenticator) {

  def load = AdminHtmlAction.authenticated { implicit request =>
    searchClient.updateURIGraph()
    Ok(s"indexed users")
  }


  def update(userId: Id[User]) = AdminHtmlAction.authenticated { implicit request =>
    // bump up seqNum
    val bookmarks = db.readOnly { implicit s => bookmarkRepo.getByUser(userId) }
    bookmarks.grouped(1000).foreach { group =>
      db.readWrite { implicit s => group.foreach(bookmarkRepo.save) }
    }

    val collections = db.readOnly(implicit s => collectionRepo.getByUser(userId))
    collections.grouped(1000).foreach{group =>
      db.readWrite( implicit s => group.foreach(collectionRepo.save))
    }

    searchClient.updateURIGraph()
    Ok(s"indexed users")
  }

  def reindex = AdminHtmlAction.authenticated { implicit request =>
    searchClient.reindexURIGraph()
    Ok("reindexing started")
  }

  def dumpLuceneDocument(id: Id[User]) =  AdminHtmlAction.authenticated { implicit request =>
    Async {
      searchClient.dumpLuceneURIGraph(id).map(Ok(_))
    }
  }

  def dumpCollectionLuceneDocument(id: Id[Collection]) =  AdminHtmlAction.authenticated { implicit request =>
    Async {
      val collection = db.readOnly { implicit s => collectionRepo.get(id) }
      searchClient.dumpLuceneCollection(id, collection.userId).map(Ok(_))
    }
  }
}
