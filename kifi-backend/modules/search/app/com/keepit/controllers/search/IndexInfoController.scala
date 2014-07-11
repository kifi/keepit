package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import play.api.libs.json.Json
import play.api.mvc.Action
import com.keepit.search.article.ArticleIndexerPlugin
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.graph.collection.CollectionGraphPlugin
import com.keepit.search.graph.user._
import com.keepit.search.user.UserIndexerPlugin
import com.keepit.search.message.MessageIndexerPlugin
import com.keepit.search.phrasedetector.PhraseIndexerPlugin

class IndexInfoController @Inject() (
    articleIndexerPlugin: ArticleIndexerPlugin,
    urlGraphPlugin: URIGraphPlugin,
    collectionGraphPlugin: CollectionGraphPlugin,
    userIndexerPlugin: UserIndexerPlugin,
    userGraphPlugin: UserGraphPlugin,
    searchFriendPlugin: SearchFriendGraphPlugin,
    messageIndexerPlugin: MessageIndexerPlugin,
    phraseIndexerPlugin: PhraseIndexerPlugin) extends SearchServiceController {

  def listAll() = Action { implicit request =>
    val infos = (
      articleIndexerPlugin.indexInfos ++
      urlGraphPlugin.indexInfos ++
      collectionGraphPlugin.indexInfos ++
      userIndexerPlugin.indexInfos ++
      userGraphPlugin.indexInfos ++
      searchFriendPlugin.indexInfos ++
      messageIndexerPlugin.indexInfos ++
      phraseIndexerPlugin.indexInfos
    )
    Ok(Json.toJson(infos))
  }
}
