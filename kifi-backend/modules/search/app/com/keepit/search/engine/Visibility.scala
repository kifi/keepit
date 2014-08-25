package com.keepit.search.engine

object Visibility { // use value class?
  // NOTE: only 7 bits for record type.
  val RESTRICTED = 0x00

  val OTHERS = 0x01
  val NETWORK = 0x02
  val MEMBER = 0x04
  val OWNER = 0x08
  //  unused = 0x10
  //  unused = 0x20
  val HAS_SECONDARY_ID = 0x40
}
