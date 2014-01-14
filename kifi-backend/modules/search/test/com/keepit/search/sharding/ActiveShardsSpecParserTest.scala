package com.keepit.search.sharding

import org.specs2.mutable._

class ActiveShardsSpecParserTest extends Specification {
  val parser = new ActiveShardsSpecParser

  "ActiveShardsSpecParser" should {
    "parse None" in {
      parser.parse(None) === ActiveShards(Seq(Shard(0,1)))
    }
    "parse the empty string" in {
      parser.parse(Some("")) === ActiveShards(Seq(Shard(0,1)))
    }
    "parse well-formed specs" in {
      parser.parse(Some("0/1")) === ActiveShards(Seq(Shard(0,1)))
      parser.parse(Some("1,2/3")) === ActiveShards(Seq(Shard(1,3), Shard(2,3)))
      parser.parse(Some("1, 5, 10 / 20")) === ActiveShards(Seq(Shard(1,20), Shard(5,20), Shard(10,20)))
    }
    "throws exception when parsing a bad spec" in {
      parser.parse(Some("a/3")) must throwAn[Exception]
      parser.parse(Some("1/b")) must throwAn[Exception]
      parser.parse(Some("-1/3")) must throwAn[Exception]
      parser.parse(Some("0/0")) must throwAn[Exception]
      parser.parse(Some("1/-3")) must throwAn[Exception]
      parser.parse(Some("3/3")) must throwAn[Exception]
    }
  }
}