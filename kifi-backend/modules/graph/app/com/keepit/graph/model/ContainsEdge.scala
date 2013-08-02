package com.keepit.graph.model

import play.api.libs.json._
import com.keepit.common.db.{Id, State}
import play.api.libs.functional.syntax._
import com.keepit.model.KeepToCollection

case class ContainsData(id: Id[KeepToCollection], state: State[KeepToCollection]) extends EdgeData

object ContainsData extends Companion[ContainsData] {

  case object CONTAINS extends TypeCode[ContainsData]
  implicit val typeCode = CONTAINS

  implicit val format = (
    (__ \ 'id).format(Id.format[KeepToCollection]) and
    (__ \ 'state).format(State.format[KeepToCollection])
    )(ContainsData.apply, unlift(ContainsData.unapply))

  def apply(keepToCollection: KeepToCollection): ContainsData = ContainsData(keepToCollection.id.get, keepToCollection.state)
}

