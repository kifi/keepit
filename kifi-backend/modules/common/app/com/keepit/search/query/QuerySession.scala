package com.keepit.search.query

import scala.collection.immutable.Queue
import scala.concurrent.duration._

import org.joda.time.DateTime

import com.keepit.common.cache._
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.NormalizedURI
import com.keepit.model.User

import play.api.libs.functional.syntax._
import play.api.libs.json._


case class QuerySession(
  val userId: Id[User],
  val queries: Queue[QueryEntered] = Queue.empty[QueryEntered],
  val uriClicks: Queue[URIClicked] = Queue.empty[URIClicked]
){
  lazy val lastEvent: Option[QuerySessionEvent] = {
    (queries.lastOption, uriClicks.lastOption) match {
      case (None, None) => None
      case (Some(q), None) => Some(q)
      case (None, Some(c)) => throw new IllegalQuerySessionStateException("Illegal QuerySession state: click queue should be empty when query queue is empty")
      case (Some(q), Some(c)) => if (q.timeStamp.getMillis < c.timeStamp.getMillis) Some(c) else Some(q)
    }
  }
  
  def addEvent(newEvent: QuerySessionEvent): AddEventResult = {
    def addEventToEmptySession(userId: Id[User], newEvent: QuerySessionEvent) = {
      newEvent match {
        case e: QueryEntered => QuerySession(userId, queries = Queue(newEvent.asInstanceOf[QueryEntered]))
        case e: URIClicked => QuerySession(userId)           // session should only start with QueryEntered event
      }
    }
    
    val classifier = ActionClassifier(this, newEvent)
    
    classifier.classify match {
      case DISCARD_EVENT => AddEventResult(None, this)
      
      case DISCARD_CURRENT_SESSION => AddEventResult(None, addEventToEmptySession(this.userId, newEvent))
      
      case GROW_CURRENT_SESSION =>
        newEvent match {
          case e: QueryEntered =>
            val newQueries = if (!queries.isEmpty &&
                (newEvent.timeStamp.getMillis - queries.last.timeStamp.getMillis) < QuerySession.slowTypingThreshold) {
              queries.dropRight(1).enqueue(newEvent.asInstanceOf[QueryEntered])
            } else queries.enqueue(newEvent.asInstanceOf[QueryEntered])
            
            AddEventResult(None, QuerySession(this.userId, newQueries, this.uriClicks))
            
          case e: URIClicked => val newUriClicks = uriClicks.enqueue(newEvent.asInstanceOf[URIClicked])
            AddEventResult(None, QuerySession(this.userId, this.queries, newUriClicks))
        }
      
      case COMPLETE_CURRENT_SESSION =>
        val lastClickTime = this.uriClicks.last.timeStamp.getMillis
        val trimmedQueries = this.queries.filter(_.timeStamp.getMillis < lastClickTime)
        AddEventResult(
          toSave = Some(QuerySession(this.userId, trimmedQueries, this.uriClicks)),
          toCache = addEventToEmptySession(this.userId, newEvent)
        )
    }
  }
}

class IllegalQuerySessionStateException(msg: String) extends Exception(msg: String)

case class ActionClassifier(session: QuerySession, newEvent: QuerySessionEvent) {
  
  lazy val gapToLastEvent = session.lastEvent match {
    case None => 0L
    case Some(event) =>
      val gap = newEvent.getTimeStamp.getMillis - session.lastEvent.get.getTimeStamp.getMillis
      if (gap == 0) 1 else gap            // add a small perturbation
  }
  
  lazy val isSessionBoundary = gapToLastEvent > QuerySession.sessionBoundaryThreshold
    
  lazy val shouldDiscardEvent: Boolean = {
    if (gapToLastEvent < 0) true
    else if (gapToLastEvent > 0) false
    else {
      newEvent match {
        case e: QueryEntered => false
        case e: URIClicked => true
      }
    }
  }
  
  lazy val shouldGrowCurrentSession = !shouldDiscardEvent && !isSessionBoundary
  
  lazy val shouldDiscardCurrentSession = !shouldDiscardEvent && isSessionBoundary && session.uriClicks.isEmpty
  
  lazy val shouldCompleteCurrentSession = !shouldDiscardEvent && isSessionBoundary && !shouldDiscardCurrentSession
  
  def classify(): ActionForNewEvent = {
    if (shouldDiscardEvent) DISCARD_EVENT
    else if (shouldGrowCurrentSession) GROW_CURRENT_SESSION
    else if (shouldDiscardCurrentSession) DISCARD_CURRENT_SESSION
    else COMPLETE_CURRENT_SESSION
  }
}

sealed trait ActionForNewEvent

case object DISCARD_EVENT extends ActionForNewEvent
case object DISCARD_CURRENT_SESSION extends ActionForNewEvent
case object GROW_CURRENT_SESSION extends ActionForNewEvent
case object COMPLETE_CURRENT_SESSION extends ActionForNewEvent

/**
 * toSave: this is not None ONLY IF we discover a complete query session. We will save the session info somewhere.
 * toCache: always update to cache
 */
case class AddEventResult(toSave: Option[QuerySession], toCache: QuerySession)

object QuerySession {
  implicit val userIdFormat = Id.format[User]
  
  implicit val queryEnteredFormat = (
    (__ \ 'queryString).format[String] and
    (__ \'timeStamp).format(DateTimeJsonFormat)
  )(QueryEntered.apply, unlift(QueryEntered.unapply))
  
  implicit val uriClickedFormat = (
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'timeStamp).format(DateTimeJsonFormat)
  )(URIClicked.apply, unlift(URIClicked.unapply))
  
  implicit def querySessionFormat = (
    (__ \ 'userId).format[Id[User]] and
    (__ \'queries).format[Queue[QueryEntered]] and
    (__ \'uriClicks).format[Queue[URIClicked]]
  )(QuerySession.apply, unlift(QuerySession.unapply))
  
  val sessionBoundaryThreshold = 1000*60        // milliseconds
  val slowTypingThreshold = 1000                // milliseconds
}

sealed trait QuerySessionEvent{
  val timeStamp: DateTime
  def getTimeStamp = timeStamp
}

case class QueryEntered(
  val queryString: String,
  val timeStamp: DateTime
) extends QuerySessionEvent

case class URIClicked(
  val uriId: Id[NormalizedURI],
  val timeStamp: DateTime
) extends QuerySessionEvent

case class QuerySessionKey(userId: Id[User]) extends Key[QuerySession]{
  override val version = 1
  val namespace = "query_session_by_user_id"
  def toKey(): String = userId.id.toString
}

class QuerySessionCache(
  stats: CacheStatistics,
  accessLog: AccessLog,
  innermostPluginSettings: (FortyTwoCachePlugin, Duration),
  innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*
) extends JsonCacheImpl[QuerySessionKey, QuerySession](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

