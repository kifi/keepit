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
import com.keepit.controllers.CommonActions._
import com.keepit.model.Bookmark
import com.keepit.common.db.CX

object BookmarksController extends Controller {
  
  def addBookmarks() = JsonAction { request =>
    parseBookmarks(request.body \ "bookmarks") 
    parseUserInfo(request.body \ "user_info") 
    Ok(JsObject(List("status" -> JsString("success"))))
  }
  
  private def parseBookmarks(value: JsValue): Unit = value match {
    case JsArray(elements) => elements map parseBookmarks
    case json: JsObject if(json.keys.contains("children")) => parseBookmarks( json \ "children" )  
    case json: JsObject => parseBookmark( json )  
    case e => throw new Exception("can't figure what to do with %s".format(e))  
  }
  
  private def parseUserInfo(value: JsValue): Unit = {
    val fbId = (value \ "facebook_id").as[String]
    println("fbId:")
    println(fbId)
  }
  
  private def parseBookmark(json: JsObject) = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    CX.withConnection { implicit conn =>
      Bookmark(title, url).save
    }
  }
  
}