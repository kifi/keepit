package com.keepit.search.sharding

import org.specs2.mutable._

class ShardTest extends Specification {
  "Shard" should {

    "generate correct indexn name suffix" in {
      Shard(0, 1).indexNameSuffix === ""
      Shard(0, 5).indexNameSuffix === "_0of5"
      Shard(1, 5).indexNameSuffix === "_1of5"
      Shard(2, 5).indexNameSuffix === "_2of5"
      Shard(3, 10).indexNameSuffix === "_3of10"
    }

    "determine if an id belongs to it" in {
      Shard(0, 1).contains(0L) === true
      Shard(0, 1).contains(1L) === true
      Shard(0, 1).contains(2L) === true

      Shard(0, 3).contains(0L) === true
      Shard(0, 3).contains(1L) === false
      Shard(0, 3).contains(2L) === false
      Shard(0, 3).contains(3L) === true

      Shard(1, 3).contains(0L) === false
      Shard(1, 3).contains(1L) === true
      Shard(1, 3).contains(2L) === false
      Shard(1, 3).contains(3L) === false

      Shard(2, 3).contains(0L) === false
      Shard(2, 3).contains(1L) === false
      Shard(2, 3).contains(2L) === true
      Shard(1, 3).contains(3L) === false
    }
  }
}
