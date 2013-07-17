package com.keepit.graph.model

import com.keepit.common.db.State
import com.keepit.model.{Collection, NormalizedURI, User}

sealed trait VertexData {
  type DbType
  val state: State[DbType]
}

case class UserData(state: State[User]) extends VertexData { type DbType = User }
case class UriData(state: State[NormalizedURI]) extends VertexData { type DbType = NormalizedURI }
case class CollectionData(state: State[Collection]) extends VertexData { type DbType = Collection }

object UserData {
  def apply(user: User): UserData = UserData(user.state)
}

object UriData {
  def apply(uri: NormalizedURI): UriData = UriData(uri.state)
}

object CollectionData {
  def apply(collection: Collection): CollectionData = CollectionData(collection.state)
}
