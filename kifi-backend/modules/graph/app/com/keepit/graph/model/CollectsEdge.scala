package com.keepit.graph.model

import com.keepit.model.Collection
import play.api.libs.json._
import com.keepit.common.db.{Id, State}
import play.api.libs.functional.syntax._

case class CollectsData(id: Id[Collection], state: State[Collection]) extends EdgeData

object CollectsData extends TypeProvider[CollectsData] {

  implicit val typeCode = TypeCode('COLLECTS)

  def apply(collection: Collection): CollectsData = CollectsData(collection.id.get, collection.state)

  implicit val format = (
    (__ \ 'id).format(Id.format[Collection]) and
    (__ \ 'state).format(State.format[Collection])
    )(CollectsData.apply, unlift(CollectsData.unapply))
}
