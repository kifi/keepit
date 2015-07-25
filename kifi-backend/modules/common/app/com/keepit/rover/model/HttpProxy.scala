package com.keepit.rover.model

import com.keepit.common.db.{ State, States, Id }

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class HttpProxy(
    id: Option[Id[HttpProxy]] = None,
    state: State[HttpProxy] = HttpProxyStates.ACTIVE,
    alias: String,
    host: String,
    port: Int,
    scheme: ProxyScheme,
    username: Option[String],
    password: Option[String]) {

  def isActive = state == HttpProxyStates.ACTIVE

  def authenticationStr = username.map(
    _ + password.map(":" + _).getOrElse("") + "@"
  ).getOrElse("")

  def toSelect =
    s"""
       |$alias
       | (
       |${ProxyScheme.toName(scheme)}://
       |$authenticationStr
       |$host:$port
       |)
     """.stripMargin.replaceAll("\n", "")

}

object HttpProxyStates extends States[HttpProxy]

sealed class ProxyScheme(val name: String)

object ProxyScheme {

  case object Https extends ProxyScheme("https")
  case object Http extends ProxyScheme("http")

  val schemes = List(Http, Https)
  val schemesMap = schemes.map(s => s.name -> s).toMap

  def fromName(name: String): ProxyScheme = schemesMap.get(name.toLowerCase).get
  val toName: ProxyScheme => String = (_: ProxyScheme).name

  implicit val schemeReads = __.read[String].map(fromName)
  implicit val schemeWrites: Writes[ProxyScheme] = new Writes[ProxyScheme] {
    override def writes(o: ProxyScheme): JsValue = JsString(toName(o))
  }

}

object HttpProxy {

  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[HttpProxy]) and
    (__ \ 'state).format[State[HttpProxy]] and
    (__ \ 'alias).format[String] and
    (__ \ 'host).format[String] and
    (__ \ 'port).format[Int] and
    (__ \ 'scheme).format[ProxyScheme] and
    (__ \ 'username).formatNullable[String] and
    (__ \ 'password).formatNullable[String]
  )(HttpProxy.apply, unlift(HttpProxy.unapply))

}
