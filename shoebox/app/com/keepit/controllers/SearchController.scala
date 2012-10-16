package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import play.api.libs.json.JsArray
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.inject._
import com.keepit.serializer.BookmarkSerializer
import com.keepit.serializer.{URIPersonalSearchResultSerializer => BPSRS}
import com.keepit.common.db.ExternalId
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api.http.ContentTypes
import play.api.libs.json.JsString
import com.keepit.common.logging.Logging
import com.keepit.search.index.ArticleIndexer

object SearchController extends Controller with Logging {
 
  def search(term: String) = Action { request =>
    println("searching with T%s".format(term))
    val searchRes = inject[ArticleIndexer].search(term)
    val augmentedRes = CX.withConnection { implicit c =>
      searchRes map { hit =>
        (NormalizedURI.get(Id[NormalizedURI](hit.id)), hit.score)
      }
    }
    println(augmentedRes mkString "\n")
    Ok(augmentedRes mkString "\n").as(ContentTypes.TEXT)
  }
 
}