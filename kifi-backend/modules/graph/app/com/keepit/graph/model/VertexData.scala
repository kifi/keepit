package com.keepit.graph.model

sealed trait VertexData[V <: VertexDataReader] { self: V =>
  def asReader: V = self
}
case class UserData(id: VertexDataId[UserReader]) extends UserReader with VertexData[UserReader]
case class UriData(id: VertexDataId[UriReader]) extends UriReader with VertexData[UriReader]
case class TagData(id: VertexDataId[TagReader]) extends TagReader with VertexData[TagReader]
case class ThreadData(id: VertexDataId[ThreadReader]) extends ThreadReader with VertexData[ThreadReader]
case class FacebookAccountData(id: VertexDataId[FacebookAccountReader]) extends FacebookAccountReader with VertexData[FacebookAccountReader]
case class LinkedInAccountData(id: VertexDataId[LinkedInAccountReader]) extends LinkedInAccountReader with VertexData[LinkedInAccountReader]
case class LDATopicData(id: VertexDataId[LDATopicReader]) extends LDATopicReader with VertexData[LDATopicReader]
case class KeepData(id: VertexDataId[KeepReader]) extends KeepReader with VertexData[KeepReader]
