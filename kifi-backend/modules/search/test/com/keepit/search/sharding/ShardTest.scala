package com.keepit.search.sharding

import org.specs2.mutable._
import com.keepit.common.db.Id

class ShardTest extends Specification {
  "Shard" should {

    def shard(i: Int, n: Int) = Shard[Any](i, n)
    implicit def longToId(i: Long) = Id[Any](i)

    "generate correct indexn name suffix" in {
      shard(0, 1).indexNameSuffix === ""
      shard(0, 5).indexNameSuffix === "_0of5"
      shard(1, 5).indexNameSuffix === "_1of5"
      shard(2, 5).indexNameSuffix === "_2of5"
      shard(3, 10).indexNameSuffix === "_3of10"
    }

    "determine if an id belongs to it" in {
      shard(0, 1).contains(0L) === true
      shard(0, 1).contains(1L) === true
      shard(0, 1).contains(2L) === true

      shard(0, 3).contains(0L) === true
      shard(0, 3).contains(1L) === false
      shard(0, 3).contains(2L) === false
      shard(0, 3).contains(3L) === true

      shard(1, 3).contains(0L) === false
      shard(1, 3).contains(1L) === true
      shard(1, 3).contains(2L) === false
      shard(1, 3).contains(3L) === false

      shard(2, 3).contains(0L) === false
      shard(2, 3).contains(1L) === false
      shard(2, 3).contains(2L) === true
      shard(1, 3).contains(3L) === false
    }
  }
}
