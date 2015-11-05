package com.keepit.common.reflection

import scala.collection.mutable
import scala.language.experimental.macros
import scala.language.postfixOps
import scala.reflect.macros.blackbox

object EnumerationMacro {
  def findValuesImpl[A: c.WeakTypeTag](c: blackbox.Context): c.Expr[IndexedSeq[A]] = {
    import c.universe._
    val resultType = implicitly[c.WeakTypeTag[A]].tpe
    val typeSymbol = weakTypeOf[A].typeSymbol
    val subclassSymbols = enclosedSubClasses(c)(typeSymbol).toList
    if (subclassSymbols.isEmpty) {
      c.Expr[IndexedSeq[A]](reify(IndexedSeq.empty[A]).tree)
    } else {
      c.Expr[IndexedSeq[A]](
        Apply(
          TypeApply(
            Select(reify(IndexedSeq).tree, TermName("apply")),
            List(TypeTree(resultType))
          ),
          subclassSymbols.map(Ident(_))
        )
      )
    }
  }
  private[this] def enclosedSubClasses(c: blackbox.Context)(target: c.universe.Symbol): Set[c.universe.Symbol] = {
    val start = c.internal.enclosingOwner.owner
    val q = mutable.Queue(start)
    val visited = mutable.Set.empty[c.universe.Symbol]
    while (q.nonEmpty) {
      val cur = q.dequeue()
      if (!visited.contains(cur)) {
        visited += cur
        val children = cur.typeSignature.decls.toList.filter { s => s.isModule || s.isClass }
        q.enqueue(children: _*)
      }
    }
    visited.filter { s =>
      try { s.asModule.moduleClass.asClass.baseClasses.contains(target) } catch { case _: Throwable => false }
    }.toSet
  }
}
