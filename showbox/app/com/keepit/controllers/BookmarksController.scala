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
    val json = request.body
    val (bookmarks, userInfo) = parseJson(json)
    println(bookmarks mkString "\n")
    Ok(JsObject(List("status" -> JsString("success"))))
  }
  
  private def parseJson(json: JsValue) = try {
    val bookmarks = parseBookmarks(json \ "bookmarks") 
    val userInfo = parseUserInfo(json \ "user_info")
    (bookmarks, userInfo)
  } catch {
    case e => 
      println("Error parsing %s".format(json))
      e.printStackTrace()
      throw e
  }
  
  private def parseBookmarks(value: JsValue): List[Bookmark] = value match {
    case JsArray(elements) => (elements map parseBookmarks flatten).toList  
    case json: JsObject if(json.keys.contains("children")) => parseBookmarks( json \ "children" )  
    case json: JsObject => List(parseBookmark(json))  
    case e => throw new Exception("can't figure what to do with %s".format(e))  
  }
  
  private def parseUserInfo(value: JsValue): Unit = {
    val fbId = (value \ "facebook_id").as[String]
    println("fbId:")
    println(fbId)
  }
  
  private def parseBookmark(json: JsObject): Bookmark = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    CX.withConnection { implicit conn =>
      Bookmark(title, url).save
    }
  }
  
}