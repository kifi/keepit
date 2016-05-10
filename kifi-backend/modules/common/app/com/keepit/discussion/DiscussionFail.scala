package com.keepit.discussion

import com.keepit.common.reflection.Enumerator
import play.api.http.Status._
import play.api.libs.json._
import play.api.mvc.Results.Status

sealed abstract class DiscussionFail(val status: Int, val err: String) extends Exception(err) {
  def asErrorResponse = Status(status)(Json.obj("error" -> err))
}

object DiscussionFail extends Enumerator[DiscussionFail] {
  case object INSUFFICIENT_PERMISSIONS extends DiscussionFail(FORBIDDEN, "insufficient_permissions")
  case object INVALID_KEEP_ID extends DiscussionFail(BAD_REQUEST, "specified_keep_does_not_exist")
  case object INVALID_USER_ID extends DiscussionFail(BAD_REQUEST, "invalid_user_id")
  case object INVALID_LIBRARY_ID extends DiscussionFail(BAD_REQUEST, "invalid_library_id")
  case object INVALID_MESSAGE_ID extends DiscussionFail(BAD_REQUEST, "specified_message_does_not_exist")
  case object MESSAGE_DOES_NOT_EXIST_ON_KEEP extends DiscussionFail(BAD_REQUEST, "message_does_not_exist_on_specified_keep")
  case object URI_COLLISION extends DiscussionFail(BAD_REQUEST, "uri_collision_in_library")
  case object NO_NEW_PARTICIPANTS extends DiscussionFail(BAD_REQUEST, "no_new_participants")

  // The most generic input failure. As we create new endpoints we should create specific failures
  // This is here strictly because I'm lazy
  case object COULD_NOT_PARSE extends DiscussionFail(BAD_REQUEST, "could_not_parse_request")
  case object MISSING_MESSAGE_TEXT extends DiscussionFail(BAD_REQUEST, "no_text_provided")
}

