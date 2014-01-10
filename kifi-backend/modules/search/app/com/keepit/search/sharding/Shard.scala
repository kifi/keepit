package com.keepit.search.sharding

import com.keepit.common.db.Id
import scala.util.parsing.combinator.RegexParsers

case class Shard(shardId: Int, numShards: Int) {

  def indexNameSuffix: String = {
    if (shardId == 0 && numShards == 1) "" else s"_${shardId}of${numShards}"
  }

  def contains[T](id: Id[T]): Boolean = contains(id.id)
  def contains(id: Long): Boolean = ((id % numShards.toLong) == shardId.toLong)
}

case class ActiveShards(shards: Seq[Shard]) {
  def find[T](id: Id[T]): Option[Shard] = find(id.id)
  def find(id: Long): Option[Shard] = shards.find(_.contains(id))
}

object ActiveShardsSpecParser extends RegexParsers {

  def apply(configValue: Option[String]): ActiveShards = {
    ActiveShards(configValue.map{ v =>
      val trimmed = v.trim
      if (trimmed.length > 0) parseAll(spec, v).get else Seq(Shard(0,1))
    }.getOrElse(Seq(Shard(0,1))))
  }

  def spec: Parser[Seq[Shard]] = rep1sep(num, ",") ~ "/" ~ num ^^{
    case ids ~ "/" ~ numShards => ids.map{ id =>
       if (numShards <= 0) throw new Exception(s"numShards=$id")
       if (id < 0 || id >= numShards) throw new Exception(s"shard id $id is out of range [0, $numShards]")
       Shard(id, numShards)
    }
  }

  def num: Parser[Int] = """\d+""".r ^^ { _.toInt }
}

