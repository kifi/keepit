package com.keepit.common.cache

trait Key[T] {
  val namespace: String
  val version: Int = 1
  def toKey(): String
  private def escapedKey()
  override final def toString: String = namespace + "%" + version + "#" + escapedKey()
}

