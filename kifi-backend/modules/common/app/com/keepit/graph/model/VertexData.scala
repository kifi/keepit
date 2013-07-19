package com.keepit.graph.model

import com.keepit.common.db.State
import com.keepit.model.{Collection, NormalizedURI, User}
import scala.reflect.runtime.universe._
import play.api.libs.json._

sealed trait VertexData {
  type DbType
  val state: State[DbType]
}

case class UserData(state: State[User]) extends VertexData { type DbType = User }
case class UriData(state: State[NormalizedURI]) extends VertexData { type DbType = NormalizedURI }
case class CollectionData(state: State[Collection]) extends VertexData { type DbType = Collection }

object VertexData {
  def fourBitRepresentation[T <: VertexData]()(implicit tag: TypeTag[T]): Int = tag.tpe match {
    case t if t =:= typeOf[UserData] => 0
    case t if t =:= typeOf[UriData] => 1
    case t if t =:= typeOf[CollectionData] => 2
  }

  def format[V <: VertexData]()(implicit tag: TypeTag[V]): Format[V] = tag.tpe match {
    case t if t =:= typeOf[UserData] => UserData.format.asInstanceOf[Format[V]]
    case t if t =:= typeOf[UriData] => UriData.format.asInstanceOf[Format[V]]
    case t if t =:= typeOf[CollectionData] => CollectionData.format.asInstanceOf[Format[V]]
  }
}

object UserData {
  def apply(user: User): UserData = UserData(user.state)

  implicit val format = Format(
      new Reads[UserData] { def reads(o: JsValue) = State.format[User].reads(o.as[JsObject] \ "state").map(UserData.apply) },
      new Writes[UserData] { def writes(userData: UserData) = JsObject(Seq(("state", State.format[User].writes(userData.state)))) }
    )

}

object UriData {
  def apply(uri: NormalizedURI): UriData = UriData(uri.state)

  implicit val format = Format(
    new Reads[UriData] { def reads(o: JsValue) = State.format[NormalizedURI].reads(o.as[JsObject] \ "state").map(UriData.apply) },
    new Writes[UriData] { def writes(uriData: UriData) = JsObject(Seq(("state", State.format[NormalizedURI].writes(uriData.state)))) }
  )
}

object CollectionData {
  def apply(collection: Collection): CollectionData = CollectionData(collection.state)

  implicit val format = Format(
    new Reads[CollectionData] { def reads(o: JsValue) = State.format[Collection].reads(o.as[JsObject] \ "state").map(CollectionData.apply) },
    new Writes[CollectionData] { def writes(collectionData: CollectionData) = JsObject(Seq(("state", State.format[Collection].writes(collectionData.state)))) }
  )
}
