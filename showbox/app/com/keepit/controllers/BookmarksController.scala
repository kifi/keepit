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
import com.keepit.model._
import com.keepit.serializer.BookmarkSerializer
import com.keepit.serializer.{BookmarkPersonalSearchResultSerializer => BPSRS}
import play.api.libs.json.JsArray
import java.util.concurrent.TimeUnit
import com.keepit.common.db.ExternalId
import com.keepit.model.BookmarkSearchResults
import play.api.http.ContentTypes

//note: users.size != count if some users has the bookmark marked as private
case class BookmarkPersonalSearchResult(bookmark: Bookmark, count: Int, users: Seq[User], score: Float)

object BookmarksController extends Controller {

  def all = Action{ request =>
    val bookmarks = CX.withConnection { implicit conn =>
      Bookmark.all
    }
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }
  
  def allView = Action{ request =>
    val bookmarks = CX.withConnection { implicit conn =>
      Bookmark.all
    }
    Ok(views.html.bookmarks(bookmarks))
  }  
  
  def addBookmarks() = JsonAction { request =>
    val json = request.body
    println(json)
    val facebookId = parseUserInfo(json \ "user_info")
    val user = internUser(facebookId)
    val bookmarks = parseBookmarks(json \ "bookmarks", user) 
    println(user)
    Ok(JsObject(List("status" -> JsString("success"))))
  }
  
  private def internUser(facebookId: FacebookId): User = CX.withConnection { implicit conn =>
    User.getOpt(facebookId) match {
      case Some(user) => user
      case None =>
        val json = try {
          Json.parse(WS.url("https://graph.facebook.com/" + facebookId.value).get().await(30, TimeUnit.SECONDS).get.body)
        } catch {
          case e =>
            e.printStackTrace()
            Json.parse("""{"first_name": "NA", "last_name": "NA"}""")
        }
        println("fb obj = " + json)
        User(
            firstName = (json \ "first_name").as[String],
            lastName = (json \ "last_name").as[String],
            facebookId = Some(facebookId)
        ).save
    }
  }
    
  private def parseBookmarks(value: JsValue, user: User): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => parseBookmarks(e, user)} flatten).toList  
    case json: JsObject if(json.keys.contains("children")) => parseBookmarks( json \ "children" , user)  
    case json: JsObject => List(parseBookmark(json, user))  
    case e => throw new Exception("can't figure what to do with %s".format(e))  
  }
  
  private def parseUserInfo(value: JsValue): FacebookId = FacebookId((value \ "facebook_id").as[String])
  
  private def parseBookmark(json: JsObject, user: User): Bookmark = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    CX.withConnection { implicit conn =>
      val bookmark = Bookmark(title, url, user)
      bookmark.loadUsingHash match {
        case Some(existing) =>
          println("bookmark %s already exist in db, not persisting!".format(existing))
          existing
        case None => 
          println("new bookmark %s".format(bookmark))
          bookmark.save
      }
    }
  }
  
  def searchBookmarks(term: String, facebookUser: String) = Action { request =>
    val res = CX.withConnection { implicit conn =>
      val user = User.getOpt(FacebookId(facebookUser)).get
      val res: Seq[BookmarkSearchResults] = Bookmark.search(term)
      res map { r =>
        toPersonalSearchResult(r, user)
      }
    }
    Ok(BPSRS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }
  
  private[controllers] def toPersonalSearchResult(res: BookmarkSearchResults, user: User): BookmarkPersonalSearchResult = {
    val bookmark = res.bookmarks.filter(_.userId == user.id).head
    val count = res.bookmarks.size
    val users = res.bookmarks.map(_.userId.get).map{ userId =>
      CX.withConnection { implicit c =>
        User.get(userId)
      }
    }
    BookmarkPersonalSearchResult(bookmark, count, users, res.score)
  }
  
  
  def orderResults(res: Map[Bookmark, Int]): List[(Bookmark, Int)] = res.toList.sortWith{
    (a, b) => a._2 > b._2
  }
  
}