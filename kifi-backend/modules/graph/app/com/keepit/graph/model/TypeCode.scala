package com.keepit.graph.model

trait TypeProvider[+T] {
  implicit def typeCode: TypeCode[T]
  implicit val typeProvider: TypeProvider[T] = this
  implicit def instanceToTypeProvider[I >: T](instance: I): TypeProvider[I] = this
}

case class TypeCode[+T](code: Symbol) {
  override def toString() = code.name
}
