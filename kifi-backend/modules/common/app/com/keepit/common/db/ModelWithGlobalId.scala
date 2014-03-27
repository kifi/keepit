package com.keepit.common.db

import com.keepit.model.{User, Reflect}

case class TypeHeader[-G <: ModelWithGlobalId](code: Short) {
  require(code > 0, "Type header must be positive")
  def shifted: Long = code.toLong << 48
}

case class GlobalId(id: Long) extends AnyVal {
  def code: Short = (id >> 48).toShort
  def value: Long = id & GlobalId.maxId
  def toId[M <: ModelWithGlobalId: TypeHeader]: Id[M] = {
    val header = implicitly[TypeHeader[M]]
    require((code == header.code), "Invalid GlobalId")
    Id[M](value)
  }
  override def toString() = ModelWithGlobalId(code) + "|" + value
}

object GlobalId {
  val maxId: Long = (1.toLong << 48) - 1
  def apply[G <: ModelWithGlobalId: TypeHeader, M <: G](id: Id[M]): GlobalId = {
    val header = implicitly[TypeHeader[G]]
    require(id.id <= maxId, "Id too large to be globalized")
    GlobalId((header.shifted | id.id))
  }
}

trait GlobalIdCompanion {
  type G <: ModelWithGlobalId
  implicit def header: TypeHeader[G]
}

object ModelWithGlobalId {
  val all: Set[GlobalIdCompanion] = Reflect.getCompanionTypeSystem[ModelWithGlobalId, GlobalIdCompanion]("G")
  private val byHeaderCode = {
    require(all.size == all.map(_.header).size, "Duplicate GlobalId headers")
    all.map { companion => companion.header.code -> companion }.toMap
  }
  def apply(headerCode: Short): GlobalIdCompanion = byHeaderCode(headerCode)
}


sealed trait ModelWithGlobalId {
  type G <: ModelWithGlobalId
  type M <: G
  def id: Option[Id[M]]
  def globalId(implicit header: TypeHeader[G]): Option[GlobalId] = id.map(GlobalId[G, M](_))
}

trait UserWithGlobalId extends ModelWithGlobalId { self: User => type G = UserWithGlobalId; type M = User }

case object UserWithGlobalId extends GlobalIdCompanion {
  type G = UserWithGlobalId
  val header = TypeHeader[G](1)
}
