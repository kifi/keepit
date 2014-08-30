package com.keepit.search.engine

object Visibility { // use value class?
  // NOTE: this is stored as the record type in DataBuffer. Only 7 bits are available for record type.
  val RESTRICTED = 0x00

  val OTHERS = 0x01
  val NETWORK = 0x02
  val MEMBER = 0x04
  val OWNER = 0x08
  //  unused = 0x10
  val HAS_SECONDARY_ID = (0x20 | 0x40) // make it easier to compare (see ScoreContext)
  val HAS_TERTIARY_ID = 0x40

  val ALTERNATIVE_ID_MASK = (0x20 | 0x40)
}
