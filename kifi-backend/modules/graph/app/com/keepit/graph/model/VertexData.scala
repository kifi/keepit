package com.keepit.graph.model


sealed trait VertexData[V <: VertexDataReader] { self: V =>
  def asReader: V = self
}
case class UserData(id: VertexDataId[UserReader]) extends UserReader with VertexData[UserReader]
case class UriData(id: VertexDataId[UriReader]) extends UriReader with VertexData[UriReader]
case class TagData(id: VertexDataId[TagReader]) extends TagReader with VertexData[TagReader]
case class ThreadData(id: VertexDataId[ThreadReader]) extends ThreadReader with VertexData[ThreadReader]
