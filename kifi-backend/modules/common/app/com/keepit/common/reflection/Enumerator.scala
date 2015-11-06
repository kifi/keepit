package com.keepit.common.reflection

trait Enumerator[T] {
  protected def _all: Seq[T] = macro EnumerationMacro.findValuesImpl[T]
}
