package com.keepit.curator.model

import org.specs2.mutable.Specification
import play.api.libs.json.Json

class SeedItemTest extends Specification {
  "uriScores serialize to compact json" in {
    var old = UriScores(1f / 3, 1f / 5, 1f / 7, 1f / 11, 1f / 13, 1f / 17, 1f / 19, 1f / 23, Some(1f / 29), Some(1f / 31), Some(1f / 37), Some(1f / 41), Some(1))
    old.prettyJson().toString() === """{"s":0.3333,"p":0.2,"oI":0.1428,"rI":0.0909,"r":0.0769,"g":0.0588,"rk":0.0526,"d":0.0434,"c":0.0344,"m":0.0322,"lb":0.027,"t1m":0.0243,"t1":1}"""
    var back = Json.fromJson[UriScores](old.prettyJson()).get
    back === old.withReducedPrecision()

    old = UriScores(1f / 3, 1f / 5, 1f / 7, 1f / 11, 1f / 13, 1f / 17, 1f / 19, 1f / 23, Some(1f / 29), Some(1f / 31), Some(1f / 37))
    old.prettyJson().toString() === """{"s":0.3333,"p":0.2,"oI":0.1428,"rI":0.0909,"r":0.0769,"g":0.0588,"rk":0.0526,"d":0.0434,"c":0.0344,"m":0.0322,"lb":0.027}"""
    back = Json.fromJson[UriScores](old.prettyJson()).get
    back === old.withReducedPrecision()
  }
}
