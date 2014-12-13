package com.keepit.search.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.search.index.graph.keep.KeepIndexerPlugin
import com.keepit.search.index.graph.library.LibraryIndexerPlugin
import play.api.libs.json.Json
import play.api.mvc.Action
import com.keepit.search.index.article.ArticleIndexerPlugin
import com.keepit.search.index.graph.collection.CollectionGraphPlugin
import com.keepit.search.index.graph.user._
import com.keepit.search.index.user.UserIndexerPlugin
import com.keepit.search.index.message.MessageIndexerPlugin
import com.keepit.search.index.phrase.PhraseIndexerPlugin

class IndexController @Inject() (
    articleIndexerPlugin: ArticleIndexerPlugin,
    collectionGraphPlugin: CollectionGraphPlugin,
    userIndexerPlugin: UserIndexerPlugin,
    userGraphPlugin: UserGraphPlugin,
    searchFriendPlugin: SearchFriendGraphPlugin,
    messageIndexerPlugin: MessageIndexerPlugin,
    keepIndexerPlugin: KeepIndexerPlugin,
    libraryIndexerPlugin: LibraryIndexerPlugin,
    phraseIndexerPlugin: PhraseIndexerPlugin) extends SearchServiceController {

  def updateKeepIndex() = Action { implicit request =>
    keepIndexerPlugin.update()
    collectionGraphPlugin.update() // still needed for compatibility support
    Ok
  }

  def updateLibraryIndex() = Action { implicit request =>
    libraryIndexerPlugin.update()
    Ok
  }

  def listIndexInfo() = Action { implicit request =>
    val infos = (
      articleIndexerPlugin.indexInfos ++
      collectionGraphPlugin.indexInfos ++
      userIndexerPlugin.indexInfos ++
      userGraphPlugin.indexInfos ++
      searchFriendPlugin.indexInfos ++
      messageIndexerPlugin.indexInfos ++
      keepIndexerPlugin.indexInfos ++
      libraryIndexerPlugin.indexInfos ++
      phraseIndexerPlugin.indexInfos
    )
    Ok(Json.toJson(infos))
  }
}
