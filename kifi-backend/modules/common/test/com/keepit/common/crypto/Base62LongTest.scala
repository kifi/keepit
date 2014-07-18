package com.keepit.common.crypto

import org.specs2.mutable._

class Base62LongTest extends Specification {
  "Base62Long" should {
    "encode" in {
      Base62Long.encode(0) === "AAAAAAAAAAA"
      Base62Long.encode(1) === "AAAAAAAAAAB"
      Base62Long.encode(-1) === "9AAAAAAAAAA"
      Base62Long.encode(1234567890123456L) === "AAFojQHiLB2"
      Base62Long.encode(-1234567890123456L) === "9AFojQHiLB1"
      Base62Long.encode(Long.MinValue) === "z9VIxAiFIwH"
      Base62Long.encode(Long.MaxValue) === "K9VIxAiFIwH"
    }
    "decode" in {
      Base62Long.decode("AAAAAAAAAAA") === 0
      Base62Long.decode("AAAAAAAAAAB") === 1
      Base62Long.decode("9AAAAAAAAAA") === -1
      Base62Long.decode("AAFojQHiLB2") === 1234567890123456L
      Base62Long.decode("9AFojQHiLB1") === -1234567890123456L
      Base62Long.decode("z9VIxAiFIwH") === Long.MinValue
      Base62Long.decode("K9VIxAiFIwH") === Long.MaxValue
    }
  }
}
