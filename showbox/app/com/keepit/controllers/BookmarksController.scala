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
import com.keepit.common.db.CX
import com.keepit.model.Bookmark
import com.keepit.model.FacebookId
import com.keepit.model.User

object BookmarksController extends Controller {
  
  def addBookmarks() = JsonAction { request =>
    val json = request.body
    val facebookId = parseUserInfo(json \ "user_info")
    val user = internUser(facebookId)
    val bookmarks = parseBookmarks(json \ "bookmarks", user) 
    println(bookmarks mkString "\n")
    println(user)
    Ok(JsObject(List("status" -> JsString("success"))))
  }
  
  private def internUser(facebookId: FacebookId): User = CX.withConnection { implicit conn =>
    User.intern(facebookId)
  }
    
  private def parseBookmarks(value: JsValue, user: User): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => parseBookmarks(e, user)} flatten).toList  
    case json: JsObject if(json.keys.contains("children")) => parseBookmarks( json \ "children" , user)  
    case json: JsObject => List(parseBookmark(json, user))  
    case e => throw new Exception("can't figure what to do with %s".format(e))  
  }
  
  private def parseUserInfo(value: JsValue): FacebookId = {
    val fbId = FacebookId((value \ "facebook_id").as[String])
    println("fbId:" + fbId)
    fbId
  }
  
  private def parseBookmark(json: JsObject, user: User): Bookmark = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    CX.withConnection { implicit conn =>
      Bookmark(title, url, user).save
    }
  }
  
}