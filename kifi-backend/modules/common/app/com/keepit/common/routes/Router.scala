package com.keepit.common.routes


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
}

abstract class Method(name: String)
case object GET extends Method("GET")
case object POST extends Method("POST")
case object PUT extends Method("PUT")

object shoebox extends Service {
  object service {
    def getNormalizedURI(id: Long) = ServiceRoute(GET, "internal/shoebox/database/getNormalizedURI", Param("id", id))
  }


}

object search extends Service {

}

