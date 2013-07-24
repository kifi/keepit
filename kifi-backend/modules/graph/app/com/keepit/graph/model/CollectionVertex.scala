package com.keepit.graph.model

import com.keepit.common.db.{Id, State}
import com.keepit.model.Collection
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class CollectionVertex(id: VertexId[CollectionData], data: CollectionData) extends Vertex[CollectionData]

case class CollectionData(id: Id[Collection], state: State[Collection]) extends VertexData

object CollectionData {
  def apply(collection: Collection): CollectionData = CollectionData(collection.id.get, collection.state)

  implicit val format = (
    (__ \ 'id).format(Id.format[Collection]) and
    (__ \ 'state).format(State.format[Collection])
    )(CollectionData.apply, unlift(CollectionData.unapply))
}
