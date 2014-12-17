package com.keepit.search.index.sharding

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import scala.util.parsing.combinator.RegexParsers

case class Shard[T](shardId: Int, numShards: Int) {

  def indexNameSuffix: String = {
    if (shardId == 0 && numShards == 1) "" else s"_${shardId}_${numShards}"
  }

  def contains(id: Id[T]): Boolean = ((id.id % numShards.toLong) == shardId.toLong)
}

case class ActiveShards(local: Set[Shard[NormalizedURI]]) {
  def find(id: Id[NormalizedURI]): Option[Shard[NormalizedURI]] = local.find(_.contains(id))

  lazy val all = {
    val numShards = local.head.numShards
    (0 until numShards).map { i => Shard[NormalizedURI](i, numShards) }.toSet
  }
  lazy val remote = all -- local
}

object ShardSpec {
  def toString[T](shards: Set[Shard[T]]): String = {
    if (shards.isEmpty) throw new Exception("no shard specified")

    val numShards = shards.head.numShards
    if (!shards.forall(_.numShards == numShards)) throw new Exception("inconsistent total number of shards")

    s"${shards.map(_.shardId).mkString(",")}/$numShards"
  }
}

class ShardSpecParser {

  private class ParserImpl extends RegexParsers {

    def spec[T]: Parser[Set[Shard[T]]] = rep1sep(num, ",") ~ "/" ~ num ^^ {
      case ids ~ "/" ~ numShards => ids.map { id =>
        if (numShards <= 0) throw new Exception(s"numShards=$id")
        if (id < 0 || id >= numShards) throw new Exception(s"shard id $id is out of range [0, $numShards]")
        Shard[T](id, numShards)
      }.toSet
    }

    def num: Parser[Int] = """\d+""".r ^^ { _.toInt }
  }

  private[this] val parser = new ParserImpl

  def parse[T](spec: String): Set[Shard[T]] = parser.parseAll(parser.spec[T], spec).get
}
