package com.keepit.heimdal

import play.api.libs.json.{JsString, JsSuccess, JsValue, Format}

case class EventType(name: String)

object EventType {
  implicit val format = new Format[EventType] {
    def reads(json: JsValue) = JsSuccess(EventType(json.as[String]))
    def writes(eventType: EventType) = JsString(eventType.name)
  }
}

object UserEventTypes {
  // Growth
  val SIGNUP = EventType("signup")
  val EXTENSION_INSTALL = EventType("extension_install")
  val INVITE_SENT = EventType("invite_sent")

  // Activity
  val CONNECTED = EventType("connected")

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

  // Maintenance
  val EXT_ERROR = EventType("ext_error")
}

object SystemEventTypes {
  val IMPORTED_DOMAIN_TAGS = EventType("imported_domain_tags")
}