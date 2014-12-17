package com.keepit.search.index.sharding

import org.specs2.mutable._

class ShardSpecParserTest extends Specification {
  val parser = new ShardSpecParser

  "ShardSpecParser" should {
    "parse the empty string" in {
      parser.parse("") must throwAn[Exception]
    }
    "parse well-formed specs" in {
      parser.parse("0/1") === Set(Shard(0, 1))
      parser.parse("1,2/3") === Set(Shard(1, 3), Shard(2, 3))
      parser.parse("1, 5, 10 / 20") === Set(Shard(1, 20), Shard(5, 20), Shard(10, 20))
    }
    "throws exception when parsing a bad spec" in {
      parser.parse("a/3") must throwAn[Exception]
      parser.parse("1/b") must throwAn[Exception]
      parser.parse("-1/3") must throwAn[Exception]
      parser.parse("0/0") must throwAn[Exception]
      parser.parse("1/-3") must throwAn[Exception]
      parser.parse("3/3") must throwAn[Exception]
    }
  }
}
