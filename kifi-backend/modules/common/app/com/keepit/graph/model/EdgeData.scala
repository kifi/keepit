package com.keepit.graph.model

import com.keepit.common.db.State
import com.keepit.model._
import play.api.libs.json._
import scala.reflect.runtime.universe._

sealed trait EdgeData {
  type DbType
  val state: State[DbType]
}

case class KeptData(state: State[Bookmark]) extends EdgeData { type DbType = Bookmark }
case class FollowsData(state: State[UserConnection]) extends EdgeData { type DbType = UserConnection }
case class CollectsData(state: State[Collection]) extends EdgeData { type DbType = Collection }
case class ContainsData(state: State[KeepToCollection]) extends EdgeData { type DbType = KeepToCollection }

object EdgeData {

  def oneByteRepresentation[T]()(implicit tag: TypeTag[T]): Int = tag.tpe match {
    case t if t =:= typeOf[KeptData] => 0
    case t if t =:= typeOf[FollowsData] => 1
    case t if t =:= typeOf[CollectsData] => 2
    case t if t =:= typeOf[ContainsData] => 3
  }

  def format[E <: EdgeData]()(implicit tag: TypeTag[E]): Format[E] = tag.tpe match {
    case t if t =:= typeOf[KeptData] => KeptData.format.asInstanceOf[Format[E]]
    case t if t =:= typeOf[FollowsData] => FollowsData.format.asInstanceOf[Format[E]]
    case t if t =:= typeOf[CollectsData] => CollectsData.format.asInstanceOf[Format[E]]
    case t if t =:= typeOf[ContainsData] => ContainsData.format.asInstanceOf[Format[E]]
  }

}
object KeptData {
  def apply(bookmark: Bookmark): KeptData = KeptData(bookmark.state)

  implicit val format = Format(
    new Reads[KeptData] { def reads(o: JsValue) = State.format[Bookmark].reads(o.as[JsObject] \ "state").map(KeptData.apply) },
    new Writes[KeptData] { def writes(keptData: KeptData) = JsObject(Seq(("state", State.format[Bookmark].writes(keptData.state)))) }
  )
}

object FollowsData {
  def apply(userConnection: UserConnection): FollowsData = FollowsData(userConnection.state)

  implicit val format = Format(
    new Reads[FollowsData] { def reads(o: JsValue) = State.format[UserConnection].reads(o.as[JsObject] \ "state").map(FollowsData.apply) },
    new Writes[FollowsData] { def writes(followsData: FollowsData) = JsObject(Seq(("state", State.format[UserConnection].writes(followsData.state)))) }
  )
}

object CollectsData {
  def apply(collection: Collection): CollectsData = CollectsData(collection.state)

  implicit val format = Format(
    new Reads[CollectsData] { def reads(o: JsValue) = State.format[Collection].reads(o.as[JsObject] \ "state").map(CollectsData.apply) },
    new Writes[CollectsData] { def writes(collectsData: CollectsData) = JsObject(Seq(("state", State.format[Collection].writes(collectsData.state)))) }
  )
}

object ContainsData {
  def apply(keepToCollection: KeepToCollection): ContainsData = ContainsData(keepToCollection.state)

  implicit val format = Format(
    new Reads[ContainsData] { def reads(o: JsValue) = State.format[KeepToCollection].reads(o.as[JsObject] \ "state").map(ContainsData.apply) },
    new Writes[ContainsData] { def writes(containsData: ContainsData) = JsObject(Seq(("state", State.format[KeepToCollection].writes(containsData.state)))) }
  )
}
