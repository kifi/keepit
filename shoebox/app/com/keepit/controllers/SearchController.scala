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
import com.keepit.search.index.Hit
import com.keepit.common.social.UserWithSocial

//note: users.size != count if some users has the bookmark marked as private
case class PersonalSearchResult(uri: NormalizedURI, count: Int, users: Seq[UserWithSocial], score: Float)

object SearchController extends Controller with Logging {
 
  def search(term: String, keepitId: Id[User]) = Action { request =>
    println("searching with %s using keepit id %s".format(term, keepitId))
    val searchRes = inject[ArticleIndexer].search(term)
    val res = CX.withConnection { implicit conn =>
      val user = User.getOpt(keepitId).getOrElse(
          throw new Exception("keepit id %s not found for term %s".format(keepitId, term)))
      searchRes map { r =>
        toPersonalSearchResult(r, user)
      }
    }
    println(res mkString "\n")
    Ok(BPSRS.resSerializer.writes(res)).as(ContentTypes.JSON)
  }
  
  private[controllers] def toPersonalSearchResult(res: Hit, user: User)(implicit conn: Connection): PersonalSearchResult = {
    val uri = NormalizedURI.get(Id[NormalizedURI](res.id))
    val count = uri.bookmarks().size
    val users = uri.bookmarks().map(_.userId.get).map{ userId =>
      val user = User.get(userId) 
      val info = SocialUserInfo.getByUser(user.id.get).head
      UserWithSocial(user, info)
    }
    PersonalSearchResult(uri, count, users, res.score)
  }
  
}