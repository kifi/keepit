package com.keepit.graph.model

import com.keepit.common.db.State
import com.keepit.model.{Collection, NormalizedURI, User}

trait VertexData
sealed trait RealVertexData[T] extends VertexData {
  val state: State[T]
}

case class UserData(state: State[User]) extends RealVertexData[User]
case class UriData(state: State[NormalizedURI]) extends RealVertexData[NormalizedURI]
case class CollectionData(state: State[Collection]) extends RealVertexData[Collection]

object UserData {
  def apply(user: User): UserData = UserData(user.state)
}

object UriData {
  def apply(uri: NormalizedURI): UriData = UriData(uri.state)
}

object CollectionData {
  def apply(collection: Collection): CollectionData = CollectionData(collection.state)
}
