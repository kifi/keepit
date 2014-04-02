package com.keepit.common.db

import com.keepit.model.{User, Keep, Reflect}

case class TypeHeader[M <: ModelGlobalId[M]](code: Short) {
  require(code > 0, "Type header must be positive")
  def shifted: Long = code.toLong << 48
}

case class GlobalId(id: Long) extends AnyVal {
  def code: Short = (id >> 48).toShort
  def value: Long = id & GlobalId.maxId
  def toId[M <: ModelGlobalId[M]](implicit header: TypeHeader[M]): Id[M] = {
    require((code == header.code), "Invalid GlobalId")
    Id[M](value)
  }
  override def toString() = ModelGlobalId(code) + "|" + value
}

object GlobalId {
  val maxId: Long = (1.toLong << 48) - 1
  def apply[M <: ModelGlobalId[M]](id: Id[M])(implicit header: TypeHeader[M]): GlobalId = {
    require(id.id <= maxId, "Id too large to be globalized")
    GlobalId((header.shifted | id.id))
  }
}

trait GlobalIdCompanion {
  type M <: ModelGlobalId[M]
  type G <: ModelGlobalId[M]
  implicit def header: TypeHeader[M]
}

object ModelGlobalId {
  val all: Set[GlobalIdCompanion] = Reflect.getCompanionTypeSystem[ModelGlobalId[_], GlobalIdCompanion]("G")
  private val byHeaderCode = {
    require(all.size == all.map(_.header).size, "Duplicate GlobalId headers")
    all.map { companion => companion.header.code -> companion }.toMap
  }
  def apply(headerCode: Short): GlobalIdCompanion = byHeaderCode(headerCode)
}

sealed trait ModelGlobalId[M <: ModelGlobalId[M]] { self: Model[M] =>
  type G <: ModelGlobalId[M]
  def globalId(implicit header: TypeHeader[M]): Option[GlobalId] = id.map(GlobalId[M](_))
}

trait UserGlobalId extends ModelGlobalId[User] { self: User => type G = UserGlobalId; }

case object UserGlobalId extends GlobalIdCompanion {
  type G = UserGlobalId
  type M = User
  val header = TypeHeader[M](1)
}

trait KeepGlobalId extends ModelGlobalId[Keep] { self: Keep => type G = KeepGlobalId; }

case object KeepGlobalId extends GlobalIdCompanion {
  type G = KeepGlobalId
  type M = Keep
  val header = TypeHeader[M](2)
}
