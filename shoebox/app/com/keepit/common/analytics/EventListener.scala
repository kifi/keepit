package com.keepit.common.analytics

import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString}
import com.keepit.inject._
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URINormalizer
import com.keepit.search.ArticleSearchResultRef
import play.api.Play.current
import java.sql.Connection
import play.api.Plugin
import com.keepit.common.healthcheck.HealthcheckPlugin
import java.util.{Set => JSet}
import com.google.inject.Inject
import scala.collection.JavaConversions._
import com.keepit.search.ResultClickTracker
import com.keepit.search.BrowsingHistoryTracker
import com.keepit.search.ClickHistoryTracker

trait EventListenerPlugin extends Plugin {
  def onEvent: PartialFunction[Event,Unit]

  case class SearchMeta(query: String, url: String, normUrl: Option[NormalizedURI], queryUUID: Option[ExternalId[ArticleSearchResultRef]])
  def searchParser(externalUser: ExternalId[User], json: JsObject)(implicit s: RSession) = {
    val query = (json \ "query").asOpt[String].getOrElse("")
    val url = (json \ "url").asOpt[String].getOrElse("")
    val user = inject[UserRepo].get(externalUser)
    val normUrl = inject[NormalizedURIRepo].getByNormalizedUrl(url)
    val queryUUID = ExternalId.asOpt[ArticleSearchResultRef]((json \ "queryUUID").asOpt[String].getOrElse(""))
    (user, SearchMeta(query, url, normUrl, queryUUID))
  }
}

class EventHelper @Inject() (listeners: JSet[EventListenerPlugin]) {
  def newEvent(event: Event): Seq[String] = {
    val events = listeners.filter(_.onEvent.isDefinedAt(event))
    events.map(_.onEvent(event))
    events.map(_.getClass.getSimpleName.replaceAll("\\$","")).toSeq
  }
}

class KifiResultClickedListener extends EventListenerPlugin {
  private lazy val resultClickTracker = inject[ResultClickTracker]
  private lazy val clickHistoryTracker = inject[ClickHistoryTracker]

  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_,UserEventMetadata(EventFamilies.SEARCH,"kifiResultClicked",externalUser,_,experiments,metaData,_),_,_) =>
      val (user, meta, bookmark) = inject[DBConnection].readOnly {implicit s =>
        val (user, meta) = searchParser(externalUser, metaData)
        val bookmarkRepo = inject[BookmarkRepo]
        val bookmark = meta.normUrl.map(n => bookmarkRepo.getByUriAndUser(n.id.get,user.id.get)).flatten
        (user, meta, bookmark)
      }
      meta.normUrl.foreach{ n =>
        resultClickTracker.add(user.id.get, meta.query, n.id.get, !bookmark.isEmpty)
        clickHistoryTracker.add(user.id.get, n.id.get)
      }
  }
}

class UsefulPageListener extends EventListenerPlugin {
  private lazy val browsingHistoryTracker = inject[BrowsingHistoryTracker]

  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "usefulPage", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, url, normUrl) = inject[DBConnection].readOnly {implicit s =>
        val user = inject[UserRepo].get(externalUser)
        val url = (metaData \ "url").asOpt[String].getOrElse("")
        val normUrl = inject[NormalizedURIRepo].getByNormalizedUrl(url)
        (user, url, normUrl)
      }
      normUrl.foreach(n => browsingHistoryTracker.add(user.id.get, n.id.get))
  }
}

class SliderShownListener extends EventListenerPlugin {
  private lazy val sliderHistoryTracker = inject[SliderHistoryTracker]

  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "sliderShown", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, url, normUrl) = inject[DBConnection].readOnly {implicit s =>
        val user = inject[UserRepo].get(externalUser)
        val url = (metaData \ "url").asOpt[String].getOrElse("")
        val normUrl = inject[NormalizedURIRepo].getByNormalizedUrl(url)
        (user, url, normUrl)
      }
      normUrl.foreach(n => sliderHistoryTracker.add(user.id.get, n.id.get))
  }
}

class DeadQueryListener extends EventListenerPlugin {
  def onEvent: PartialFunction[Event,Unit] = {
    case Event(_,UserEventMetadata(EventFamilies.SEARCH,"searchUnload",externalUser,_,experiments,metaData,_),_,_)
      if (metaData \ "kifiClicked").asOpt[String].getOrElse("-1").toDouble.toInt == 0 =>
  }
}
