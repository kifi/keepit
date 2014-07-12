package com.keepit.macros

import scala.reflect.macros._
import scala.language.experimental.macros
import scala.annotation.StaticAnnotation

/**
 * @json macro annotation for case classes
 *
 * This macro annotation automatically creates a JSON serializer for the annotated case class.
 * The companion object will be automatically created if it does not already exist.
 *
 * If the case class has more than one field, the default Play formatter is used.
 * If the case class has only one field, the field is directly serialized. For example, if A
 * is defined as:
 *
 *     case class A(value: Int)
 *
 * then A(4) will be serialized as '4' instead of '{value: 4}'.
 */
class json extends StaticAnnotation {
  def macroTransform(annottees: Any*) = macro jsonMacro.impl
}

object jsonMacro {
  // Possible improvements:
  // do not add format if there is already one
  // avoid name conflicts (hygiene)
  // support case classes with generic types (requires adding type parameter to macro annotation)
  def impl(c: Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._
    annottees.map(_.tree) match {
      case (classDecl: ClassDef) :: l => {
        try {
          val q"case class $className(..$fields) extends ..$classBases { ..$body }" = classDecl
          val format = if (fields.length == 0) {
            return c.abort(c.enclosingPosition, "Cannot create json formatter for case class with no fields")
          } else if (fields.length == 1) {
            // Only one field, use the serializer for the field
            q"""
              implicit val format = {
                import play.api.libs.json._
                Format(
                  __.read[${fields.head.tpt}].map(s => ${className.toTermName}(s)),
                  new Writes[$className] { def writes(o: $className) = Json.toJson(o.${fields.head.name}) }
                )
              }
            """
          } else {
            // More than one field, use Play's macro
            q"implicit val format = play.api.libs.json.Json.format[${classDecl.name}]"
          }
          val compDecl = l match {
            case (compDecl: ModuleDef) :: _ => {
              // Add the formatter to the existing companion object
              val q"object $obj extends ..$bases { ..$body }" = compDecl
              q"""
                object $obj extends ..$bases {
                  ..$body
                  $format
                }
              """
            }
            case _ => {
              // Create a companion object with the formatter
              q"object ${className.toTermName} { $format }"
            }
          }
          c.Expr(q"""
            $classDecl
            $compDecl
          """)
        } catch {
          case e: MatchError => return c.abort(c.enclosingPosition, "Annotation is only supported on case class")
        }
      }
      case _ => c.abort(c.enclosingPosition, "Invalid annottee")
    }
  }
}
