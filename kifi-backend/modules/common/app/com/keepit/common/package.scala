package com.keepit.common

import scala.annotation.unchecked.uncheckedVariance
import scala.reflect.runtime.universe._

package object core extends com.keepit.common.Implicits {

  // Helpful types
  type ?=>[-A, +B] = PartialFunction[A, B]
  type switch = scala.annotation.switch
  type tailrec = scala.annotation.tailrec
  type File = java.io.File

  private type ATypeTag[+A] = TypeTag[A @uncheckedVariance]
  def ?!?[A](implicit tag: ATypeTag[A]): A =
    throw new NotImplementedError(s"unimplemented value of type ${tag.tpe}")
}
