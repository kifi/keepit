package com.keepit.search.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.model.{ DetailedLibraryView }
import com.keepit.search.index.{ IndexerVersionProviders, Indexable }
import com.keepit.search.index.graph.keep.KeepIndexerPlugin
import com.keepit.search.index.graph.library.membership.{ LibraryMembershipIndexer, LibraryMembershipIndexerPlugin }
import com.keepit.search.index.graph.library.{ LibraryFields, LibraryIndexer, LibraryIndexable, LibraryIndexerPlugin }
import com.keepit.search.index.graph.organization.{ OrganizationMembershipIndexerPlugin, OrganizationIndexerPlugin }
import play.api.libs.json.Json
import play.api.mvc.Action
import com.keepit.search.index.article.{ ArticleIndexerPlugin }
import com.keepit.search.index.graph.user._
import com.keepit.search.index.user.UserIndexerPlugin
import com.keepit.search.index.message.MessageIndexerPlugin
import com.keepit.search.index.phrase.PhraseIndexerPlugin
import views.html

class IndexController @Inject() (
    articleIndexerPlugin: ArticleIndexerPlugin,
    userIndexerPlugin: UserIndexerPlugin,
    userGraphPlugin: UserGraphPlugin,
    searchFriendPlugin: SearchFriendGraphPlugin,
    messageIndexerPlugin: MessageIndexerPlugin,
    keepIndexerPlugin: KeepIndexerPlugin,
    libraryIndexerPlugin: LibraryIndexerPlugin,
    libraryIndexer: LibraryIndexer,
    libraryMembershipIndexerPlugin: LibraryMembershipIndexerPlugin,
    libraryMembershipIndexer: LibraryMembershipIndexer,
    orgIndexerPlugin: OrganizationIndexerPlugin,
    orgMemIndexerPlugin: OrganizationMembershipIndexerPlugin,
    phraseIndexerPlugin: PhraseIndexerPlugin) extends SearchServiceController {

  def updateKeepIndex() = Action { implicit request =>
    keepIndexerPlugin.update()
    Ok
  }

  def updateLibraryIndex() = Action { implicit request =>
    libraryIndexerPlugin.update()
    libraryMembershipIndexerPlugin.update()
    Ok
  }

  def listIndexInfo() = Action { implicit request =>
    val infos = (
      articleIndexerPlugin.indexInfos ++
      userIndexerPlugin.indexInfos ++
      userGraphPlugin.indexInfos ++
      searchFriendPlugin.indexInfos ++
      messageIndexerPlugin.indexInfos ++
      keepIndexerPlugin.indexInfos ++
      libraryIndexerPlugin.indexInfos ++
      libraryMembershipIndexerPlugin.indexInfos ++
      phraseIndexerPlugin.indexInfos ++
      orgIndexerPlugin.indexInfos ++
      orgMemIndexerPlugin.indexInfos
    )
    Ok(Json.toJson(infos))
  }

  def versions() = Action { implicit request =>
    Ok(Json.toJson(IndexerVersionProviders.allActiveVersions))
  }

  def getLibraryDocument = Action(parse.json) { implicit request =>
    val library = request.body.as[DetailedLibraryView]
    val indexable = new LibraryIndexable(library)
    val doc = indexable.buildDocument
    Ok(html.admin.luceneDocDump("Library", doc, Indexable.getFieldDecoder(LibraryFields.decoders)))
  }
}
