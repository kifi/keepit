package com.keepit.search.sharding

import org.specs2.mutable._

class ActiveShardsSpecParserTest extends Specification {
  "ActiveShardsSpecParser" should {
    "parse None" in {
      ActiveShardsSpecParser(None) === ActiveShards(Seq(Shard(0,1)))
    }
    "parse the empty string" in {
      ActiveShardsSpecParser(Some("")) === ActiveShards(Seq(Shard(0,1)))
    }
    "parse well-formed specs" in {
      ActiveShardsSpecParser(Some("0/1")) === ActiveShards(Seq(Shard(0,1)))
      ActiveShardsSpecParser(Some("1,2/3")) === ActiveShards(Seq(Shard(1,3), Shard(2,3)))
      ActiveShardsSpecParser(Some("1, 5, 10 / 20")) === ActiveShards(Seq(Shard(1,20), Shard(5,20), Shard(10,20)))
    }
    "throws exception when parsing a bad spec" in {
      ActiveShardsSpecParser(Some("a/3")) must throwAn[Exception]
      ActiveShardsSpecParser(Some("1/b")) must throwAn[Exception]
      ActiveShardsSpecParser(Some("-1/3")) must throwAn[Exception]
      ActiveShardsSpecParser(Some("0/0")) must throwAn[Exception]
      ActiveShardsSpecParser(Some("1/-3")) must throwAn[Exception]
      ActiveShardsSpecParser(Some("3/3")) must throwAn[Exception]
    }
  }
}