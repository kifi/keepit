package com.keepit.graph.model

import com.keepit.common.db.{Id, State}
import com.keepit.model.NormalizedURI
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class UriData(id: Id[NormalizedURI], state: State[NormalizedURI]) extends VertexData

object UriData extends TypeProvider[UriData] {

  implicit val typeCode = TypeCode('URI)

  def apply(uri: NormalizedURI): UriData = UriData(uri.id.get, uri.state)

  implicit val format = (
    (__ \ 'id).format(Id.format[NormalizedURI]) and
    (__ \ 'state).format(State.format[NormalizedURI])
    )(UriData.apply, unlift(UriData.unapply))
}
