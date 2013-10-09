package com.keepit.common.cache

trait Key[T] {
  val namespace: String
  val version: Int = 1
  def toKey(): String
  override final def toString: String = namespace + "%" + version + "#" + toKey()
}

