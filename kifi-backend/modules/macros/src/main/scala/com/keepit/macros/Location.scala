package com.keepit.macros

import scala.language.experimental.macros
import scala.reflect.macros.Context

case class Location(className: String, methodName: String, line: Int) {
  def location = s"[${className}][${methodName}]:${line}"
}

object Location {

  implicit def capture: Location = macro Location.locationMacro

  def locationMacro(x: Context): x.Expr[Location] = {
    import x.universe._
    val className = Option(x.enclosingClass).flatMap(c => Option(c.symbol))
      .map(_.toString)
      .getOrElse("")
    val methodName = Option(x.enclosingMethod)
      .flatMap(m => Option(m.symbol))
      .map(_.toString)
      .getOrElse("")
    val line = x.enclosingPosition.line
    reify(new Location(x.literal(className).splice, x.literal(methodName).splice, x.literal(line).splice))
  }
}
