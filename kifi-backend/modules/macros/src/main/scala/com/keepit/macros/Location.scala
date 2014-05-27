package com.keepit.macros

import scala.language.experimental.macros
import scala.reflect.macros.Context

class Location(val location: String)

object Location {

  implicit def capture: Location = macro Location.locationMacro

  def locationMacro(x: Context): x.Expr[Location] = {
    import x.universe._
    val className = Option(x.enclosingClass).map(_.symbol.toString).getOrElse("")
    val methodName = Option(x.enclosingMethod).map(_.symbol.toString).getOrElse("")
    val line = x.enclosingPosition.line
    val where = s"[${className}][${methodName}]:${line}"
    reify(new Location(x.literal(where).splice))
  }
}
