package com.keepit.search.query

import scala.collection.immutable.Queue

import org.joda.time.DateTime
import org.specs2.mutable.Specification

import com.keepit.common.db.Id
import com.keepit.common.time.zones.PT
import com.keepit.model.NormalizedURI
import com.keepit.model.User

import play.api.libs.json.JsSuccess
import play.api.libs.json.Json

class QuerySessionTest extends Specification{
  
  object SetupHelper {
    val user = Id[User](1)
    val t = new DateTime(2013, 2, 14, 21, 59, 0, 0, PT)
    val queries = Queue(QueryEntered("query 1", t), QueryEntered("query 2", t.plusMillis(2000)))
    val clicks = Queue(URIClicked(Id[NormalizedURI](1), t.plusMillis(3000)))
    
    val emptySession = QuerySession(user)
    val clickEmptySession = QuerySession(user, queries)
    val queryEmptySession = QuerySession(user, uriClicks = clicks)
    val defaultSession = QuerySession(user, queries, clicks)
  }
  
  "QuerySession" should {
    "correctly retrieve last event" in {
      val (queries, clicks) = (SetupHelper.queries, SetupHelper.clicks)
      val sessions = Array(SetupHelper.emptySession, SetupHelper.clickEmptySession, SetupHelper.queryEmptySession, SetupHelper.defaultSession)
      
      sessions(0).lastEvent === None
      sessions(1).lastEvent === Some(queries.last)
      sessions(2).lastEvent must throwA[IllegalQuerySessionStateException]
      sessions(3).lastEvent === Some(clicks.last)
    }
    
    "maintain correct state after discarding a new event" in {
      val session = SetupHelper.defaultSession
      val t = SetupHelper.t
      val newEvent = QueryEntered("query 0", t.minusMillis(2000))
      session.addEvent(newEvent) === AddEventResult(None, session)
    }
    
    "maintain correct state after discarding current session" in {
      val session = SetupHelper.clickEmptySession
      val t = SetupHelper.t
      val user = SetupHelper.user
      val newEvent = QueryEntered("query in new session", t.plusMinutes(5))
      session.addEvent(newEvent) === AddEventResult(None, QuerySession(user, Queue(newEvent)))
    }
    
    "maintain correct state after detecting compelte session" in {
      val session = SetupHelper.defaultSession
      val t = SetupHelper.t
      val user = SetupHelper.user
      val newEvent = QueryEntered("query in new session", t.plusMinutes(5))
      session.addEvent(newEvent) === AddEventResult(Some(session), QuerySession(user, Queue(newEvent)))
      
      // trim complete session if necessary
      val session2 = session.addEvent(QueryEntered("query 3", t.plusMillis(5000))).toCache
      session2.addEvent(newEvent) === AddEventResult(Some(session), QuerySession(user, Queue(newEvent)))
    }
    
    "correctly grow a session" in {
      val session = SetupHelper.clickEmptySession
      val t = SetupHelper.t
      val newQEvent1 = QueryEntered("query 3", t.plusMillis(2500))       // new query too close to previous one
      val AddEventResult(toSave, toCache) = session.addEvent(newQEvent1)
      toSave === None
      toCache.queries.size === 2
      toCache.queries.last === newQEvent1
      toCache.uriClicks.size === 0
      
      val newCEvent = URIClicked(Id[NormalizedURI](2), t.plusMillis(3000))
      val AddEventResult(toSave2, toCache2) = toCache.addEvent(newCEvent)
      toSave2 === None
      toCache2.queries.size === 2
      toCache2.uriClicks.size === 1
      
      val newQEvent2 = QueryEntered("query 4", t.plusMillis(5500))
      val AddEventResult(toSave3, toCache3) = toCache.addEvent(newQEvent2)
      toSave3 === None
      toCache3.queries.size == 3
      toCache3.queries.takeRight(2) === Queue(newQEvent1, newQEvent2)
    }
    
    "serialize to json" in {
      val session = SetupHelper.defaultSession
      Json.fromJson[QuerySession](Json.toJson(session)) === JsSuccess(session)
      val session2 = SetupHelper.emptySession
      Json.fromJson[QuerySession](Json.toJson(session2)) === JsSuccess(session2)
    }
  }
    
  "ActionClassifier" should {
    "discard a new event if its timeStamp is smaller than that of the last event" in {
      val session = SetupHelper.defaultSession
      val t = SetupHelper.t
      val newEvent = QueryEntered("query 0", t.minusMillis(2000))
      val classifier = ActionClassifier(session, newEvent)
      classifier.classify === DISCARD_EVENT
    }
    
    "discard a new event if it is a URIClicked event and query queue is empty" in {
      val session = SetupHelper.emptySession
      val t = SetupHelper.t
      val newEvent = URIClicked(Id[NormalizedURI](2), t.plus(2000))
      val classifier = ActionClassifier(session, newEvent)
      classifier.classify === DISCARD_EVENT
    }
    
    "discard current session if session boundary is detected and there are no clicks" in {
      val session = SetupHelper.clickEmptySession
      val t = SetupHelper.t
      val newEvent = QueryEntered("query in new session", t.plusMinutes(5))
      val classifier = ActionClassifier(session, newEvent)
      classifier.classify === DISCARD_CURRENT_SESSION
    }
    
    "grow current session if necessary" in {
      val session = SetupHelper.defaultSession
      val t = SetupHelper.t
      val newQEvent = QueryEntered("query 3", t.plusMillis(5000))
      var classifier = ActionClassifier(session, newQEvent)
      classifier.classify === GROW_CURRENT_SESSION
      val newCEvent = URIClicked(Id[NormalizedURI](2), t.plusMillis(5000))
      classifier = ActionClassifier(session, newCEvent)
      classifier.classify === GROW_CURRENT_SESSION
    }
    
    "detect complete session" in {
      val session = SetupHelper.defaultSession
      val t = SetupHelper.t
      val newEvent = QueryEntered("query in new session", t.plusMinutes(5))
      val classifier = ActionClassifier(session, newEvent)
      classifier.classify === COMPLETE_CURRENT_SESSION
    }
  }
}
