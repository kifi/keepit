package com.keepit.model

import org.specs2.mutable.Specification

class KifiVersionTest extends Specification {

  "KifiExtVersion" should {
    import KifiExtVersion.{ apply => v }
    "parse valid version strings" in {
      v("2.1.2.1") === v(2, 1, 2, 1)
      v("2.1.0.0") === v(2, 1, 0, 0)
      v("2.1.0") === v(2, 1, 0, -1)
      v("2.1") === v(2, 1, -1, -1)
      v("2") === v(2, -1)
    }
    "reject invalid version strings" in {
      v("foo") must throwA[Exception]
      v("1.2.3b") must throwA[Exception]
      v("1.2b") must throwA[Exception]
    }
    "reject invalid version numbers" in {
      v(-1, 0) must throwA[Exception]
      v(0, 0, 0, -2) must throwA[Exception]
    }
    "serialize back to a string" in {
      v(0, 0).toString === "0.0"
      v(0, 0, -1).toString === "0.0"
      v(0, 0, 0).toString === "0.0.0"
      v(0, 0, -1, -1).toString === "0.0"
      v(2, 0, 1, 0).toString === "2.0.1.0"
      v(12, 25, 2014, 111111111).toString === "12.25.2014.111111111"
    }
    "serialize back to a string without build number" in {
      v(0, 0).toStringWithoutBuild === "0.0"
      v(0, 0, -1).toStringWithoutBuild === "0.0"
      v(0, 0, 0).toStringWithoutBuild === "0.0.0"
      v(0, 0, -1, -1).toStringWithoutBuild === "0.0"
      v(2, 0, 1, 0).toStringWithoutBuild === "2.0.1"
      v(12, 25, 2014, 111111111).toStringWithoutBuild === "12.25.2014"
    }
    "order correctly" in {
      v(0, 0, 0) must be_<(v(1, 0))
      v(0, 0, 0) must be_<(v(0, 1))
      v(0, 0, 0) must be_<(v(0, 0, 1))
      v(0, 0, 2) must be_<(v(0, 0, 10))
      v(2, 1, 0) === v(2, 1, 0, 0)
      v(2, 1, 0) === v(2, 1, 0)
      v(2, 1, 0) === v(2, 1)
      v(2, 1) === v(2, 1, 0, 0)
      v(2, -1) === v(2, 0, 0, 0)
      v(2, -1) === v(2, 0, 0)
      v(2, -1) === v(2, 0)
      v(2, 1, 0) must be_<(v(3, 0, 1))
      v(3, 0, 1) must be_>(v(2, 4, 8))
      v(2, 4, 8) must be_>(v(2, 1, 0))
      v(2, 8, 9990) must be_<(v(2, 9, 22))
    }
    "have a consistent hash code when zeros are omitted" in {
      v("1.2").hashCode === v("1.2.0.0").hashCode
      v("1.2").hashCode !== v("1.2.0.1").hashCode
    }
    "never equal other version types" in {
      v(1, 4, 8) !== KifiIPhoneVersion(1, 4, 8)
      v(1, 4, 8) !== KifiAndroidVersion(1, 4, 8)
    }
    // KifiExtVersion("2.4.8") > KifiIPhoneVersion("1.4.8") // should not compile
  }

  "KifiIPhoneVersion" should {
    import KifiIPhoneVersion.{ apply => v }
    "parse valid version strings" in {
      v("2.1.2.1") === v(2, 1, 2, 1)
      v("2.1.0.0") === v(2, 1, 0, 0)
      v("2.1.0") === v(2, 1, 0, -1)
      v("2.1") === v(2, 1, -1, -1)
      v("2") === v(2, -1)
    }
    "reject invalid version strings" in {
      v("foo") must throwA[Exception]
      v("1.2.3b") must throwA[Exception]
      v("1.2b") must throwA[Exception]
    }
    "reject invalid version numbers" in {
      v(-1, 0) must throwA[Exception]
      v(0, 0, 0, -2) must throwA[Exception]
    }
    "serialize back to a string" in {
      v(0, 0).toString === "0.0"
      v(0, 0, -1).toString === "0.0"
      v(0, 0, 0).toString === "0.0.0"
      v(0, 0, -1, -1).toString === "0.0"
      v(2, 0, 1, 0).toString === "2.0.1.0"
      v(12, 25, 2014, 111111111).toString === "12.25.2014.111111111"
    }
    "serialize back to a string without build number" in {
      v(0, 0).toStringWithoutBuild === "0.0"
      v(0, 0, -1).toStringWithoutBuild === "0.0"
      v(0, 0, 0).toStringWithoutBuild === "0.0.0"
      v(0, 0, -1, -1).toStringWithoutBuild === "0.0"
      v(2, 0, 1, 0).toStringWithoutBuild === "2.0.1"
      v(12, 25, 2014, 111111111).toStringWithoutBuild === "12.25.2014"
    }
    "order correctly" in {
      v(0, 0, 0) must be_<(v(1, 0))
      v(0, 0, 0) must be_<(v(0, 1))
      v(0, 0, 0) must be_<(v(0, 0, 1))
      v(0, 0, 2) must be_<(v(0, 0, 10))
      v(2, 1, 0) === v(2, 1, 0, 0)
      v(2, 1, 0) === v(2, 1, 0)
      v(2, 1, 0) === v(2, 1)
      v(2, 1) === v(2, 1, 0, 0)
      v(2, -1) === v(2, 0, 0, 0)
      v(2, -1) === v(2, 0, 0)
      v(2, -1) === v(2, 0)
      v(2, 1, 0) must be_<(v(3, 0, 1))
      v(3, 0, 1) must be_>(v(2, 4, 8))
      v(2, 4, 8) must be_>(v(2, 1, 0))
      v(2, 8, 9990) must be_<(v(2, 9, 22))
    }
    "have a consistent hash code when zeros are omitted" in {
      v("1.2").hashCode === v("1.2.0.0").hashCode
      v("1.2").hashCode !== v("1.2.0.1").hashCode
    }
    "never equal other version types" in {
      v(1, 4, 8) !== KifiExtVersion(1, 4, 8)
      v(1, 4, 8) !== KifiAndroidVersion(1, 4, 8)
    }
    // KifiIPhoneVersion("2.4.8") > KifiAndroidVersion("1.4.8") // should not compile
  }

  "KifiAndroidVersion" should {
    import KifiAndroidVersion.{ apply => v }
    "parse valid version strings" in {
      v("2.1.2.1") === v(2, 1, 2, 1)
      v("2.1.0.0") === v(2, 1, 0, 0)
      v("2.1.0") === v(2, 1, 0, -1)
      v("2.1") === v(2, 1, -1, -1)
      v("2") === v(2, -1)
    }
    "reject invalid version strings" in {
      v("foo") must throwA[Exception]
      v("1.2.3b") must throwA[Exception]
      v("1.2b") must throwA[Exception]
    }
    "reject invalid version numbers" in {
      v(-1, 0) must throwA[Exception]
      v(0, 0, 0, -2) must throwA[Exception]
    }
    "serialize back to a string" in {
      v(0, 0).toString === "0.0"
      v(0, 0, -1).toString === "0.0"
      v(0, 0, 0).toString === "0.0.0"
      v(0, 0, -1, -1).toString === "0.0"
      v(2, 0, 1, 0).toString === "2.0.1.0"
      v(12, 25, 2014, 111111111).toString === "12.25.2014.111111111"
    }
    "serialize back to a string without build number" in {
      v(0, 0).toStringWithoutBuild === "0.0"
      v(0, 0, -1).toStringWithoutBuild === "0.0"
      v(0, 0, 0).toStringWithoutBuild === "0.0.0"
      v(0, 0, -1, -1).toStringWithoutBuild === "0.0"
      v(2, 0, 1, 0).toStringWithoutBuild === "2.0.1"
      v(12, 25, 2014, 111111111).toStringWithoutBuild === "12.25.2014"
    }
    "order correctly" in {
      v(0, 0, 0) must be_<(v(1, 0))
      v(0, 0, 0) must be_<(v(0, 1))
      v(0, 0, 0) must be_<(v(0, 0, 1))
      v(0, 0, 2) must be_<(v(0, 0, 10))
      v(2, 1, 0) === v(2, 1, 0, 0)
      v(2, 1, 0) === v(2, 1, 0)
      v(2, 1, 0) === v(2, 1)
      v(2, 1) === v(2, 1, 0, 0)
      v(2, -1) === v(2, 0, 0, 0)
      v(2, -1) === v(2, 0, 0)
      v(2, -1) === v(2, 0)
      v(2, 1, 0) must be_<(v(3, 0, 1))
      v(3, 0, 1) must be_>(v(2, 4, 8))
      v(2, 4, 8) must be_>(v(2, 1, 0))
      v(2, 8, 9990) must be_<(v(2, 9, 22))
    }
    "have a consistent hash code when zeros are omitted" in {
      v("1.2").hashCode === v("1.2.0.0").hashCode
      v("1.2").hashCode !== v("1.2.0.1").hashCode
    }
    "never equal other version types" in {
      v(1, 4, 8) !== KifiExtVersion(1, 4, 8)
      v(1, 4, 8) !== KifiIPhoneVersion(1, 4, 8)
    }
    // KifiAndroidVersion("2.4.8") > KifiExtVersion("1.4.8") // should not compile
  }

}
