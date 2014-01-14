package com.keepit.search.sharding

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import scala.util.parsing.combinator.RegexParsers

case class Shard[T](shardId: Int, numShards: Int) {

  def indexNameSuffix: String = {
    if (shardId == 0 && numShards == 1) "" else s"_${shardId}of${numShards}"
  }

  def contains(id: Id[T]): Boolean = ((id.id % numShards.toLong) == shardId.toLong)
//  def contains(id: Long): Boolean = ((id % numShards.toLong) == shardId.toLong)
}

case class ActiveShards(shards: Seq[Shard[NormalizedURI]]) {
  def find(id: Id[NormalizedURI]): Option[Shard[NormalizedURI]] = shards.find(_.contains(id))
//  def find(id: Long): Option[Shard] = shards.find(_.contains(id))
}

class ActiveShardsSpecParser  {

  private[this] val parser = new ShardsSpecParser[NormalizedURI]

  def parse(configValue: Option[String]): ActiveShards = {
      ActiveShards(configValue.map{ v =>
      val trimmed = v.trim
      if (trimmed.length > 0) parser.parseAll(parser.spec, v).get else Seq(Shard[NormalizedURI](0,1))
      }.getOrElse(Seq(Shard[NormalizedURI](0,1))))
  }
}

class ShardsSpecParser[T] extends RegexParsers {

  def spec: Parser[Seq[Shard[T]]] = rep1sep(num, ",") ~ "/" ~ num ^^{
    case ids ~ "/" ~ numShards => ids.map{ id =>
       if (numShards <= 0) throw new Exception(s"numShards=$id")
       if (id < 0 || id >= numShards) throw new Exception(s"shard id $id is out of range [0, $numShards]")
       Shard[T](id, numShards)
    }
  }

  def num: Parser[Int] = """\d+""".r ^^ { _.toInt }
}

