package com.keepit.common.db

import org.joda.time.DateTime
import com.keepit.common.time._

trait Model[M] {
  def id: Option[Id[M]]
  def withId(id: Id[M]): M
  def updateTime(now: DateTime): M
}
