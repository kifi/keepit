package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import play.api.libs.json.Json
import play.api.mvc.Action
import com.keepit.search.article.ArticleIndexerPlugin
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.graph.collection.CollectionGraphPlugin

class IndexInfoController @Inject() (
  articleIndexerPlugin: ArticleIndexerPlugin,
  urlGraphPlugin: URIGraphPlugin,
  collectionGraphPlugin: CollectionGraphPlugin
) extends SearchServiceController {

  def listAll() = Action { implicit request =>
    val infos = articleIndexerPlugin.indexInfos ++ urlGraphPlugin.indexInfos ++ collectionGraphPlugin.indexInfos
    Ok(Json.toJson(infos))
  }
}