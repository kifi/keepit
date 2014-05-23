package com.keepit.macros

import scala.language.experimental.macros
import scala.reflect.macros.Context

class CodeLocation(val location: String)

object CodeLocationMacro {

  implicit def getCodeLocation: CodeLocation = macro getCodeLocationImpl

  def getCodeLocationImpl(x: Context): x.Expr[CodeLocation] = {
    import x.universe._
    val className = Option(x.enclosingClass).map(_.symbol.toString).getOrElse("")
    val methodName = Option(x.enclosingMethod).map(_.symbol.toString).getOrElse("")
    val line = x.enclosingPosition.line
    val position = s"[${className}][${methodName}]:${line}"
    reify(new CodeLocation(x.literal(position).splice))
  }
}
