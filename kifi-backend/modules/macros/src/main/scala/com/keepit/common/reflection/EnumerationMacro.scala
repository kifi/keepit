package com.keepit.common.reflection

import scala.language.experimental.macros
import scala.language.postfixOps
import scala.reflect.macros.blackbox
import scala.util.control.NonFatal

object EnumerationMacro {
  def findValuesImpl[A: c.WeakTypeTag](c: blackbox.Context): c.Expr[IndexedSeq[A]] = {
    import c.universe._
    val resultType = implicitly[c.WeakTypeTag[A]].tpe
    val typeSymbol = weakTypeOf[A].typeSymbol
    validateType(c)(typeSymbol)
    val subclassSymbols = enclosedSubClasses(c)(typeSymbol)
    if (subclassSymbols.isEmpty) {
      c.Expr[IndexedSeq[A]](reify(IndexedSeq.empty[A]).tree)
    } else {
      c.Expr[IndexedSeq[A]](
        Apply(
          TypeApply(
            Select(reify(IndexedSeq).tree, TermName("apply")),
            List(TypeTree(resultType))
          ),
          subclassSymbols.map(Ident(_)).toList
        )
      )
    }
  }

  private[this] def validateType(c: blackbox.Context)(typeSymbol: c.universe.Symbol): Unit = {
    if (!typeSymbol.asClass.isSealed)
      c.abort(
        c.enclosingPosition,
        "You can only use findValues on sealed traits or classes"
      )
  }
  private[this] def enclosedSubClasses(c: blackbox.Context)(typeSymbol: c.universe.Symbol): Seq[c.universe.Symbol] = {
    import c.universe._
    val enclosingBodySubclasses: List[Symbol] = try {
      val enclosingModuleMembers = c.internal.enclosingOwner.owner.typeSignature.decls.toList
      enclosingModuleMembers.filter { x =>
        try x.asModule.moduleClass.asClass.baseClasses.contains(typeSymbol) catch { case _: Throwable => false }
      }
    } catch { case NonFatal(e) => c.abort(c.enclosingPosition, s"Unexpected error: ${e.getMessage}") }
    if (!enclosingBodySubclasses.forall(x => x.isModule))
      c.abort(c.enclosingPosition, "All subclasses must be objects.")
    else enclosingBodySubclasses
  }
}
