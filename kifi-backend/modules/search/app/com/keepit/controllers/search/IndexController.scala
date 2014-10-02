package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.search.graph.keep.KeepIndexerPlugin
import com.keepit.search.graph.library.LibraryIndexerPlugin
import play.api.libs.json.Json
import play.api.mvc.Action
import com.keepit.search.article.ArticleIndexerPlugin
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.graph.collection.CollectionGraphPlugin
import com.keepit.search.graph.user._
import com.keepit.search.user.UserIndexerPlugin
import com.keepit.search.message.MessageIndexerPlugin
import com.keepit.search.phrasedetector.PhraseIndexerPlugin

class IndexController @Inject() (
    articleIndexerPlugin: ArticleIndexerPlugin,
    uriGraphPlugin: URIGraphPlugin,
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
    Ok
  }

  def updateLibraryIndex() = Action { implicit request =>
    libraryIndexerPlugin.update()
    Ok
  }

  def listIndexInfo() = Action { implicit request =>
    val infos = (
      articleIndexerPlugin.indexInfos ++
      uriGraphPlugin.indexInfos ++
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
