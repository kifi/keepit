package com.keepit.controllers.admin

import play.api.data._
import java.util.concurrent.TimeUnit
import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.json.JsArray
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.JsNumber
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.controllers.CommonActions._
import com.keepit.inject._
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.model.User
import com.keepit.common.controller.FortyTwoController

object URIGraphController extends FortyTwoController {

  def load = AdminHtmlAction { implicit request => 
    val uriGraphPlugin = inject[URIGraphPlugin]
    val cnt = uriGraphPlugin.load()
    Ok("indexed %d users".format(cnt))
  }
  
  def update(userId: Id[User]) = AdminHtmlAction { implicit request =>
    val uriGraphPlugin = inject[URIGraphPlugin]
    val cnt = uriGraphPlugin.update(userId)
    Ok("indexed %d users".format(cnt))
  }
}

