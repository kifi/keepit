package com.keepit.graph.model

case class UserData(id: VertexDataId[UserReader]) extends UserReader
case class UriData(id: VertexDataId[UriReader]) extends UriReader
case class TagData(id: VertexDataId[TagReader]) extends TagReader
case class ThreadData(id: VertexDataId[ThreadReader]) extends ThreadReader
