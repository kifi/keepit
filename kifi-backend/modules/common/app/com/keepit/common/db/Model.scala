package com.keepit.common.db

import org.joda.time.DateTime
import com.keepit.common.time._

trait Model[M] {
  def id: Option[Id[M]]
  def withId(id: Id[M]): M
  def withUpdateTime(now: DateTime): M
}

trait ModelWithState[M] extends Model[M] { self: Model[M] =>
  val state: State[M]
}

trait ModelWithExternalId[M] extends Model[M] { self: Model[M] =>
  def externalId: ExternalId[M]
}

trait ModelWithSeqNumber[M] extends Model[M] { self: Model[M] =>
  val seq: SequenceNumber[M]
}

trait ModelWithMaybeCopy[M] { self: M =>
  def maybeCopy[T](curVal: M => T, newVal: Option[T], modFn: M => (T => M)) = {
    newVal.filter(_ != curVal(self)).map(modFn(self)).getOrElse(self)
  }
}

trait CommonClassLinker[M <: Model[M], T] {
  def toCommon(modelId: Id[M]): Id[T] = Id[T](modelId.id)
  def fromCommon(commonId: Id[T]): Id[M] = Id[M](commonId.id)
}
