package com.keepit.common.cache

trait Key[T] {
  val namespace: String
  protected val version: Int = 1
  protected def toKey(): String
  lazy val escapedKey = toKey().replaceAll("\\+", "++").replaceAll(" ", "+")
  override final def toString: String = namespace + "%" + version + "#" + escapedKey
}

