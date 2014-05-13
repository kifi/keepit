package com.keepit.search.sharding

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import scala.util.parsing.combinator.RegexParsers

case class Shard[T](shardId: Int, numShards: Int) {

  def indexNameSuffix: String = {
    if (shardId == 0 && numShards == 1) "" else s"_${shardId}_${numShards}"
  }

  def contains(id: Id[T]): Boolean = ((id.id % numShards.toLong) == shardId.toLong)
}

case class ActiveShards(shards: Set[Shard[NormalizedURI]]) {
  def find(id: Id[NormalizedURI]): Option[Shard[NormalizedURI]] = shards.find(_.contains(id))
}

class ShardSpecParser  {

  private class ParserImpl extends RegexParsers {

    def spec[T]: Parser[Set[Shard[T]]] = rep1sep(num, ",") ~ "/" ~ num ^^{
      case ids ~ "/" ~ numShards => ids.map{ id =>
        if (numShards <= 0) throw new Exception(s"numShards=$id")
        if (id < 0 || id >= numShards) throw new Exception(s"shard id $id is out of range [0, $numShards]")
        Shard[T](id, numShards)
      }.toSet
    }

    def num: Parser[Int] = """\d+""".r ^^ { _.toInt }
  }

  private[this] val parser = new ParserImpl

  def parse[T](configValue: Option[String]): Set[Shard[T]] = {
    configValue.map{ v =>
      val trimmed = v.trim
      if (trimmed.length > 0) parser.parseAll(parser.spec[T], v).get else Set(Shard[T](0,1))
    }.getOrElse(Set(Shard[T](0,1)))
  }
}
