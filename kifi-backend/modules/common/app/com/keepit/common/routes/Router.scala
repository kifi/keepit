package com.keepit.common.routes

import com.keepit.common.db.ExternalId
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.db.State


trait Service
case class ServiceRoute(method: Method, path: String, params: Param*) {
  override def toString = path + (if(params.nonEmpty) params.map({ p =>
    p.key + (if(p.value.value != "") "=" + p.value.value else "")
  }).mkString("&") else "")
}

case class Param(key: String, value: ParamValue = ParamValue(""))
case class ParamValue(value: String)
object ParamValue {
  implicit def stringToParam(i: String) = ParamValue(i)
  implicit def longToParam(i: Long) = ParamValue(i.toString)
  implicit def intToParam(i: Int) = ParamValue(i.toString)
  implicit def stateToParam[T](i: State[T]) = ParamValue(i.value)
  implicit def externalIdToParam[T](i: ExternalId[T]) = ParamValue(i.id)
  implicit def idToParam[T](i: Id[T]) = ParamValue(i.id.toString)
}

abstract class Method(name: String)
case object GET extends Method("GET")
case object POST extends Method("POST")
case object PUT extends Method("PUT")

object shoebox extends Service {
  object service {
    def getNormalizedURI(id: Long) = ServiceRoute(GET, "/internal/shoebox/database/getNormalizedURI", Param("id", id))
  }


}

// ------------------

// ------------------

object search extends Service {
  object service {

  }
}

