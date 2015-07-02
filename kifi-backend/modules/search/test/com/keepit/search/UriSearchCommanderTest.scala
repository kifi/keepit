package com.keepit.search

import com.keepit.search.test.SearchTestInjector
import org.specs2.mutable._
import com.keepit.search.index.sharding.ActiveShards
import com.keepit.search.index.sharding.ShardSpecParser

class UriSearchCommanderTest extends Specification with SearchTestInjector with SearchTestHelper {

  implicit private val activeShards: ActiveShards = ActiveShards((new ShardSpecParser).parse("0,1 / 2"))

  "UriSearchCommander" should {

  }
}
