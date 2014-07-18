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
    "throw when decoding certain invalid inputs" in {
      Base62Long.decode("") must throwAn[ArrayIndexOutOfBoundsException]("10")
      Base62Long.decode("0000000000") must throwAn[ArrayIndexOutOfBoundsException]("10")
      Base62Long.decode("~~~~~~~~~~~") must throwAn[ArrayIndexOutOfBoundsException]("126")
    }
    "never encode two different numbers to the same string" in {
      val r = new java.util.Random()
      val v1 = r.nextLong()
      val v2 = r.nextLong()
      val s1 = Base62Long.encode(v1)
      val s2 = Base62Long.encode(v2)
      if (v1 == v2) {
        s1 === s2
      } else {
        s1 !== s2
      }
    }
    "decode two strings to the same number in certain contrived cases" in {
      Base62Long.decode("PPPPPPPPPPP") === Base62Long.decode("3tbCUv05CTA")
      // note: encode never returns "PPPPPPPPPPP"
    }
  }
}
