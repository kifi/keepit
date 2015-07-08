package com.keepit.search.engine

import com.keepit.search.util.join.{ DataBufferReader, DataBufferWriter, DataBuffer }
import org.specs2.mutable._

class VisibilityTest extends Specification {

  "Visibility" should {
    "be successfully written to a data buffer" in {
      val buf = new DataBuffer
      val writer = new DataBufferWriter

      // turn on all visibility flags
      val recType = Visibility.OWNER | Visibility.MEMBER | Visibility.NETWORK | Visibility.OTHERS | Visibility.HAS_SECONDARY_ID | Visibility.LIB_NAME_MATCH
      buf.alloc(writer, recType, 0)

      var result: (Int, Boolean) = null
      buf.scan(new DataBufferReader) { reader =>
        result = (reader.recordType, reader.hasMore)
      }

      result == (recType, false)
    }
  }
}
