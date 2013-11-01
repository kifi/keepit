package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class EmailOptOut(
  id: Option[Id[EmailOptOut]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  address: String,
  name: String,
  value: String,
  state: State[EmailOptOut] = EmailOptOutStates.ACTIVE
) extends Model[EmailOptOut] {
  def withId(id: Id[EmailOptOut]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[EmailOptOut]) = copy(state = state)
}

object EmailOptOutStates extends States[EmailOptOut]

