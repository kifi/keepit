package com.keepit.common

package object core extends com.keepit.common.Implicits {

  // Helpful types
  type ?=>[-A, +B] = PartialFunction[A, B]
  type switch = scala.annotation.switch
  type tailrec = scala.annotation.tailrec
  type File = java.io.File
}
