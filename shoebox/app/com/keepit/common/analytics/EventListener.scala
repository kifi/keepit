package com.keepit.common.analytics

import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.search.ArticleSearchResultRef
import play.api.Play.current
import java.sql.Connection

object EventListener {
  val listeners: Seq[EventListener] = Seq(KifiResultClickedListener, GoogleResultClickedListener, DeadQueryListener)
  def newEvent(event: Event): Seq[String] = {
    val events = listeners.filter(_.onEvent.isDefinedAt(event))
    events.map(_.onEvent)
    events.map(_.getClass.getSimpleName.replaceAll("\\$",""))
  }
}

trait EventListener {
  def onEvent: PartialFunction[Event,Unit]
}

object EventHelper {
  case class SearchMeta(query: String, url: String, normUrl: Option[NormalizedURI], queryUUID: Option[ExternalId[ArticleSearchResultRef]])
  def searchParser(externalUser: ExternalId[User], json: JsObject)(implicit conn: Connection) = {
      val query = (json \ "query").asOpt[String].getOrElse("")
      val url = (json \ "url").asOpt[String].getOrElse("")
      val user = User.get(externalUser)
      val normUrl = NormalizedURI.getByNormalizedUrl(url)
      val queryUUID = ExternalId.asOpt[ArticleSearchResultRef]((json \ "queryUUID").asOpt[String].getOrElse(""))
      (user, SearchMeta(query, url, normUrl, queryUUID))
  }
}


object KifiResultClickedListener extends EventListener {
  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_,UserEventMetadata(EventFamilies.SEARCH,"kifiResultClicked",externalUser,_,experiments,metaData,_),_,_) =>
      val (user, meta, bookmark) = CX.withConnection { implicit conn =>
        val (user, meta) = EventHelper.searchParser(externalUser, metaData)
        val bookmark = meta.normUrl.map(n => Bookmark.load(n.id.get,user.id.get)).flatten
        (user, meta, bookmark)
      }

      // handle KifiResultClicked
  }
}

object GoogleResultClickedListener extends EventListener {
  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_,UserEventMetadata(EventFamilies.SEARCH,"googleResultClicked",externalUser,_,experiments,metaData,_),_,_) =>
      val (user, meta) = CX.withConnection { implicit conn =>
        val (user, meta) = EventHelper.searchParser(externalUser, metaData)
        (user, meta)
      }
      // handle GoogleResultClicked
  }
}

object DeadQueryListener extends EventListener {
  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_,UserEventMetadata(EventFamilies.SEARCH,"searchUnload",externalUser,_,experiments,metaData,_),_,_)
      if (metaData \ "kifiClicked").asOpt[String].getOrElse("-1").toDouble.toInt == 0 =>

      val (user, meta) = CX.withConnection { implicit conn =>
        val (user, meta) = EventHelper.searchParser(externalUser, metaData)
        (user, meta)
      }
      // handle DeadQuery
  }
}
