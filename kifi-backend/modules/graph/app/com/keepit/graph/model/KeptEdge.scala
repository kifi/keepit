package com.keepit.graph.model

import com.keepit.model.Bookmark
import play.api.libs.json._
import com.keepit.common.db.{Id, State}
import play.api.libs.functional.syntax._

case class KeptData(id: Id[Bookmark], state: State[Bookmark]) extends EdgeData

object KeptData extends TypeProvider[KeptData] {

  implicit val typeCode = TypeCode('KEPT)

  def apply(bookmark: Bookmark): KeptData = KeptData(bookmark.id.get,  bookmark.state)

  implicit val format = (
    (__ \ 'id).format(Id.format[Bookmark]) and
    (__ \ 'state).format(State.format[Bookmark])
    )(KeptData.apply, unlift(KeptData.unapply))
}
