package com.keepit.heimdal

import com.keepit.common.reflection.Enumerator
import play.api.libs.json.{ JsString, JsSuccess, JsValue, Format }

case class EventType(name: String)

object EventType {
  implicit val format = new Format[EventType] {
    def reads(json: JsValue) = JsSuccess(EventType(json.as[String]))
    def writes(eventType: EventType) = JsString(eventType.name)
  }
}

object UserEventTypes {
  // Growth
  val JOINED = EventType("joined")
  val INVITED = EventType("invited")
  val COMPLETED_SIGNUP = EventType("completed_signup")

  // Activity
  val CONNECTED = EventType("connected")
  val USED_KIFI = EventType("used_kifi")
  val UPDATED_EXTENSION = EventType("updated_extension")
  val VIEWED_PAGE = EventType("viewed_page")
  val VIEWED_PANE = EventType("viewed_pane")

  // Keeping
  val KEPT = EventType("kept")

  // Messaging
  val MESSAGED = EventType("messaged")

  // Notifications
  val WAS_NOTIFIED = EventType("was_notified")

  // Settings (= mute/unmute conversation before more)
  val CHANGED_SETTINGS = EventType("changed_settings")

  // Search
  val CLICKED_SEARCH_RESULT = EventType("clicked_search_result")
  val SEARCHED = EventType("searched")
  val VOTED = EventType("voted")

  // Maintenance
  val EXT_ERROR = EventType("ext_error")

  // recommendaton
  val RECOMMENDATION_USER_ACTION = EventType("reco_action")

  // Libraries
  val MODIFIED_LIBRARY = EventType("modified_library")
  val FOLLOWED_LIBRARY = EventType("followed_library")
  val VIEWED_LIBRARY = EventType("viewed_library")

  // Organizations
  val CREATED_ORGANIZATION = EventType("created_organization")

  // Content (keeps or discussions)
  val VIEWED_CONTENT = EventType("viewed_content")
}

object SystemEventTypes {
  val IMPORTED_DOMAIN_TAGS = EventType("imported_domain_tags")
  val MESSAGED = EventType("messaged")
}

object AnonymousEventTypes {
  val KEPT = EventType("kept")
  val MESSAGED = EventType("messaged")
}

object NonUserEventTypes {
  val MESSAGED = EventType("messaged")
  val WAS_NOTIFIED = EventType("was_notified")
  val SEARCHED = EventType("searched")
  val CLICKED_SEARCH_RESULT = EventType("clicked_search_result")
}

object VisitorEventTypes {
  val VIEWED_LIBRARY = EventType("viewed_library")
  val VIEWED_CONTENT = EventType("viewed_content")
}

object SlackEventTypes extends Enumerator[EventType] {
  val SEARCHED = EventType("searched")
  val CLICKED_SEARCH_RESULT = EventType("clicked_search_result")
  val WAS_NOTIFIED = EventType("was_notified")

  def contains(eventType: EventType) = _all.contains(eventType)
}
