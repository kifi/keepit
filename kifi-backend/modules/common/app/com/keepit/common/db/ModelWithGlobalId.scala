package com.keepit.common.db

import com.keepit.model.{User, Reflect}

case class TypeHeader[M <: ModelWithGlobalId[M]](code: Short) {
  require(code > 0, "Type header must be positive")
  def shifted: Long = code.toLong << 48
}

case class GlobalId(id: Long) extends AnyVal {
  def code: Short = (id >> 48).toShort
  def value: Long = id & GlobalId.maxId
  def toId[M <: ModelWithGlobalId[M]: TypeHeader]: Id[M] = {
    val header = implicitly[TypeHeader[M]]
    require((code == header.code), "Invalid GlobalId")
    Id[M](value)
  }
  override def toString() = ModelWithGlobalId(code) + "|" + value
}

object GlobalId {
  val maxId: Long = (1.toLong << 48) - 1
  def apply[M <: ModelWithGlobalId[M]: TypeHeader](id: Id[M]): GlobalId = {
    val header = implicitly[TypeHeader[M]]
    require(id.id <= maxId, "Id too large to be globalized")
    GlobalId((header.shifted | id.id))
  }
}

trait GlobalIdCompanion {
  type M <: ModelWithGlobalId[M]
  type G <: ModelWithGlobalId[M]
  implicit def header: TypeHeader[M]
}

object ModelWithGlobalId {
  val all: Set[GlobalIdCompanion] = Reflect.getCompanionTypeSystem[ModelWithGlobalId[_], GlobalIdCompanion]("G")
  private val byHeaderCode = {
    require(all.size == all.map(_.header).size, "Duplicate GlobalId headers")
    all.map { companion => companion.header.code -> companion }.toMap
  }
  def apply(headerCode: Short): GlobalIdCompanion = byHeaderCode(headerCode)
}

sealed trait ModelWithGlobalId[M <: ModelWithGlobalId[M]] { self: Model[M] =>
  type G <: ModelWithGlobalId[M]
  def id: Option[Id[M]]
  def globalId(implicit header: TypeHeader[M]): Option[GlobalId] = id.map(GlobalId[M](_))
}

trait UserWithGlobalId extends ModelWithGlobalId[User] { self: User => type G = UserWithGlobalId; }

case object UserWithGlobalId extends GlobalIdCompanion {
  type G = UserWithGlobalId
  type M = User
  val header = TypeHeader[M](1)
}
