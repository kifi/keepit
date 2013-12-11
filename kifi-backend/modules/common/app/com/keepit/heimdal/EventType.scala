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
  val WS_CONNECT = EventType("ws_connect")
  val WS_DISCONNECT = EventType("ws_disconnect")

  // Keeping
  val KEPT = EventType("kept")

  // Messaging
  val NEW_MESSAGE = EventType("new_message")
  val REPLY_MESSAGE = EventType("reply_message")

  // Notifications
  val WAS_NOTIFIED = EventType("was_notified")

  // Search
  val CLICKED_SEARCH_RESULT = EventType("clicked_search_result")
  val SEARCHED = EventType("searched")

  // Maintenance
  val EXT_ERROR = EventType("ext_error")
}

object SystemEventTypes {
  val DOMAIN_TAG_IMPORT = EventType("domain_tag_import")
}