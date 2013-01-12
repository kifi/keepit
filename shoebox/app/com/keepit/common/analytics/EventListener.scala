package com.keepit.common.analytics

import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import com.keepit.inject._
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.search.ArticleSearchResultRef
import play.api.Play.current
import java.sql.Connection
import play.api.Plugin
import com.keepit.common.healthcheck.HealthcheckPlugin
import java.util.{Set => JSet}
import com.google.inject.Inject
import scala.collection.JavaConversions._
import com.keepit.search.ResultClickTracker

trait EventListenerPlugin extends Plugin {
  def onEvent: PartialFunction[Event,Unit]

  case class SearchMeta(query: String, url: String, normUrl: Option[NormalizedURI], queryUUID: Option[ExternalId[ArticleSearchResultRef]])
  def searchParser(externalUser: ExternalId[User], json: JsObject)(implicit conn: Connection) = {
    val query = (json \ "query").asOpt[String].getOrElse("")
    val url = (json \ "url").asOpt[String].getOrElse("")
    val user = UserCxRepo.get(externalUser)
    val normUrl = NormalizedURI.getByNormalizedUrl(url)
    val queryUUID = ExternalId.asOpt[ArticleSearchResultRef]((json \ "queryUUID").asOpt[String].getOrElse(""))
    (user, SearchMeta(query, url, normUrl, queryUUID))
  }
}

class EventHelper @Inject() (listeners: JSet[EventListenerPlugin]) {
  def newEvent(event: Event): Seq[String] = {
    val events = listeners.filter(_.onEvent.isDefinedAt(event))
    events.map(_.onEvent)
    events.map(_.getClass.getSimpleName.replaceAll("\\$","")).toSeq
  }

}


class KifiResultClickedListener extends EventListenerPlugin {
  private lazy val resultClickTracker = inject[ResultClickTracker]

  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_,UserEventMetadata(EventFamilies.SEARCH,"kifiResultClicked",externalUser,_,experiments,metaData,_),_,_) =>
      val (user, meta, bookmark) = CX.withConnection { implicit conn =>
        val (user, meta) = searchParser(externalUser, metaData)
        val bookmark = meta.normUrl.map(n => Bookmark.load(n.id.get,user.id.get)).flatten
        (user, meta, bookmark)
      }

      // handle KifiResultClicked
      meta.normUrl.map(n => resultClickTracker.add(user.id.get, meta.query, n.id.get))
  }
}

class UsefulPageListener extends EventListenerPlugin {
  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_,UserEventMetadata(EventFamilies.SLIDER,"usefulPage",externalUser,_,experiments,metaData,_),_,_) =>
      val (user, url) = CX.withConnection { implicit conn =>
        val user = UserCxRepo.get(externalUser)
        val url = (metaData \ "url").asOpt[String].getOrElse("")
        (user, url)
      }
      // handle UsefulPageListener
  }
}

class DeadQueryListener extends EventListenerPlugin {
  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_,UserEventMetadata(EventFamilies.SEARCH,"searchUnload",externalUser,_,experiments,metaData,_),_,_)
      if (metaData \ "kifiClicked").asOpt[String].getOrElse("-1").toDouble.toInt == 0 =>
        // Commenting to not waste queries while this isn't used. However, the pattern here can be used elsewhere.
//      val (user, meta) = CX.withConnection { implicit conn =>
//        val (user, meta) = EventHelper.searchParser(externalUser, metaData)
//        (user, meta)
//      }
      // handle DeadQuery
  }
}
