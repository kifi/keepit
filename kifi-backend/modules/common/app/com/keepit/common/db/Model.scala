package com.keepit.common.db

import org.joda.time.DateTime
import com.keepit.common.time._

trait Model[M] {
  def id: Option[Id[M]]
  def withId(id: Id[M]): M
  def withUpdateTime(now: DateTime): M
}

trait ModelWithState[M] extends Model[M]{ self: Model[M] =>
  val state: State[M]
}

trait ModelWithExternalId[M] extends Model[M] { self: Model[M] =>
  def externalId: ExternalId[M]
}

trait ModelWithSeqNumber[M] extends Model[M] { self: Model[M]=>
  val seq: SequenceNumber
}