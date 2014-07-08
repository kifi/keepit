package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model.{KeepRepo, CollectionRepo, Collection, User}
import com.keepit.search.SearchServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class AdminURIGraphController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  keepRepo: KeepRepo,
  collectionRepo: CollectionRepo,
  searchClient: SearchServiceClient)
    extends AdminController(actionAuthenticator) {

  def load = AdminHtmlAction.authenticated { implicit request =>
    searchClient.updateURIGraph()
    Ok(s"indexed users")
  }


  def update(userId: Id[User]) = AdminHtmlAction.authenticated { implicit request =>
    // bump up seqNum
    val bookmarks = db.readOnlyMaster { implicit s => keepRepo.getByUser(userId) }
    bookmarks.grouped(1000).foreach { group =>
      db.readWrite { implicit s => group.foreach(keepRepo.save) }
    }

    val collections = db.readOnlyMaster(implicit s => collectionRepo.getUnfortunatelyIncompleteTagsByUser(userId))
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

  def dumpLuceneDocument(id: Id[User]) =  AdminHtmlAction.authenticatedAsync { implicit request =>
    searchClient.dumpLuceneURIGraph(id).map(Ok(_))
  }

  def dumpCollectionLuceneDocument(id: Id[Collection]) =  AdminHtmlAction.authenticatedAsync { implicit request =>
    val collection = db.readOnlyMaster { implicit s => collectionRepo.get(id) }
    searchClient.dumpLuceneCollection(id, collection.userId).map(Ok(_))
  }
}
