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
import com.keepit.serializer.BookmarkSerializer
import com.keepit.serializer.{URIPersonalSearchResultSerializer => BPSRS}
import com.keepit.common.db.ExternalId
import java.util.concurrent.TimeUnit
import java.sql.Connection
import play.api.http.ContentTypes
import play.api.libs.json.JsString
import com.keepit.common.logging.Logging

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchResult(uri: NormalizedURI, count: Int, users: Seq[User], score: Float)

object BookmarksController extends Controller with Logging {

  def edit(id: Id[Bookmark]) = Action{ request =>
    CX.withConnection { implicit conn =>
      val bookmark = Bookmark.get(id) 
      val user = User.get(bookmark.userId.get)
      Ok(views.html.editBookmark(bookmark, user))
    }
  }
  
  //this is an admin only task!!!
  def delete(id: Id[Bookmark]) = Action{ request =>
    CX.withConnection { implicit conn =>
      val bookmark = Bookmark.get(id)
      bookmark.delete()
      Redirect(com.keepit.controllers.routes.BookmarksController.allView)
    }
  }  
  
  def all = Action{ request =>
    val bookmarks = CX.withConnection { implicit conn =>
      Bookmark.all
    }
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }
  
  def allView = Action{ request =>
    val bookmarksAndUsers = CX.withConnection { implicit conn =>
      val bookmarks = Bookmark.all
      val users = bookmarks map (_.userId.get) map User.get
      val uris = bookmarks map (_.uriId) map NormalizedURI.get map {u => u.stats()}
      (bookmarks, uris, users).zipped.toList.seq
    }
    Ok(views.html.bookmarks(bookmarksAndUsers))
  }  
  
  def addBookmarks() = JsonAction { request =>
    val json = request.body
    log.debug(json)
    val facebookId = parseFacebookId(json \ "user_info")
    val keepitId = parseKeepitId(json \ "user_info")
    val user = internUser(facebookId, keepitId)
    parseBookmarks(json \ "bookmarks", user) 
    log.info(user)
    Ok(JsObject(("status" -> JsString("success")) :: 
        ("userId" -> JsString(user.id.map(id => id.id.toString()).getOrElse(""))) :: Nil))
  }
  
  private def internUser(facebookId: FacebookId, keepitId : Id[User]): User = CX.withConnection { implicit conn =>
    User.getOpt(keepitId) match {
      case Some(user) =>
        user
      case None => 
        User.getOpt(facebookId) match {
	      	case Some(user) => 
	      	  user
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
  }
    
  private def parseBookmarks(value: JsValue, user: User): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => parseBookmarks(e, user)} flatten).toList  
    case json: JsObject if(json.keys.contains("children")) => parseBookmarks( json \ "children" , user)  
    case json: JsObject => List(parseBookmark(json, user))  
    case e => throw new Exception("can't figure what to do with %s".format(e))  
  }
  
  private def parseFacebookId(value: JsValue): FacebookId = FacebookId((value \ "facebook_id").as[String])
  private def parseKeepitId(value: JsValue): Id[User] = Id[User](Integer.parseInt(((value \ "keepit_id").as[String])))
  
  private def parseBookmark(json: JsObject, user: User): Bookmark = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    CX.withConnection { implicit conn =>
      val normalizedUri = NormalizedURI.getByUrl(url) match {
        case Some(uri) => uri
        case None => NormalizedURI(title, url).save
      }
      Bookmark.load(normalizedUri, user) match {
        case Some(bookmark) => bookmark
        case None => Bookmark(normalizedUri, user, title, url).save
      }
    }
  }
  
  def searchBookmarks(term: String, keepitId: Id[User]) = Action { request =>
    println("searching with %s using keepit id %s".format(term, keepitId))
    val res = CX.withConnection { implicit conn =>
      val user = User.getOpt(keepitId).getOrElse(
          throw new Exception("keepi id %s not found for term %s".format(keepitId, term)))
      val res: Seq[URISearchResults] = NormalizedURI.search(term)
      res map { r =>
        toPersonalSearchResult(r, user)
      }
    }
    println(res mkString "\n")
    Ok(BPSRS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }
  
  private[controllers] def toPersonalSearchResult(res: URISearchResults, user: User)(implicit conn: Connection): PersonalSearchResult = {
    val uri = res.uri
    val count = uri.bookmarks().size
    val users = uri.bookmarks().map(_.userId.get).map{ userId =>
      User.get(userId)
    }
    PersonalSearchResult(uri, count, users, res.score)
  }
  
  def orderResults(res: Map[Bookmark, Int]): List[(Bookmark, Int)] = res.toList.sortWith{
    (a, b) => a._2 > b._2
  }
  
}