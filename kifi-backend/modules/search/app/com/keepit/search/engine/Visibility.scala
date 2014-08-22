package com.keepit.search.engine

object Visibility { // use value class?
  // NOTE: only 7 bits for record type.
  val RESTRICTED = 0x00
  val SEARCHABLE_KEEP = 0x10

  val OTHERS = 0x01
  val NETWORK = 0x02
  val MEMBER = 0x04 | SEARCHABLE_KEEP
  val OWNER = 0x08 | SEARCHABLE_KEEP
}
