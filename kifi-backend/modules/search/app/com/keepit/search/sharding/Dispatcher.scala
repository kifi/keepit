package com.keepit.search.sharding

import com.keepit.common.logging.Logging
import com.keepit.common.zookeeper.ServiceInstance
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.Random

class ShardedServiceInstance[T](val serviceInstance: ServiceInstance, val shards: Set[Shard[T]])

object Dispatcher {
  private val shardSpecParser = new ShardSpecParser

  def apply[R, T](instances: Vector[ServiceInstance]): Dispatcher[R, T] = {

    val upList = instances.filter(_.isUp)
    val availableList = instances.filter(_.isAvailable)
    val list = if (upList.length < availableList.length / 2.0) {
      availableList
    } else {
      upList
    }

    var shardToInstances = Map.empty[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]]
    list.foreach{ i =>
      val shards = shardSpecParser.parse[T](i.remoteService.amazonInstanceInfo.tags.get("shards"))
      val shardedServiceInstance = new ShardedServiceInstance[T](i, shards)
      shards.foreach{ s =>
        shardToInstances += {
          shardToInstances.get(s) match {
            case Some(buf) => s -> (buf += shardedServiceInstance)
            case None => s -> ArrayBuffer(shardedServiceInstance)
          }
        }
      }
    }
    new Dispatcher[R, T](shardToInstances)
  }
}

class Dispatcher[R, T](shardToInstances: Map[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]]) extends Logging {
  private[this] val rnd = new Random()

  def dispatch(myShards: Set[Shard[T]], allShards: Set[Shard[T]], submit: (ServiceInstance, Set[Shard[T]]) => Future[R]) = {
    var futures = new ArrayBuffer[Future[R]]
    var remaining = allShards -- myShards
    while (remaining.nonEmpty) {
      next(remaining) match {
        case (Some(ss), shards) =>
          remaining --= shards
          futures += submit(ss.serviceInstance, shards)
        case (None, _) =>
      }
    }
    futures
  }

  def next(shards: Set[Shard[T]], numTrials: Int = 0): (Option[ShardedServiceInstance[T]], Set[Shard[T]]) = {
    var numEntries = 0
    val table = shards.toSeq.map{ shard =>
      shardToInstances.get(shard) match {
        case Some(instances) =>
          numEntries += instances.size
          (numEntries, instances)
        case None =>
          log.error(s"shard not found: $shard")
          (numEntries, ArrayBuffer[ShardedServiceInstance[T]]())
      }
    }
    var bestInstance: Option[ShardedServiceInstance[T]] = None
    var bestCoverage = Set.empty[Shard[T]]
    var trials = numTrials
    while (trials > 0) {
      getCandidate(table, numEntries) match {
        case candidate @ Some(inst) =>
          val thisCoverage = (inst.shards intersect shards)
          if (thisCoverage.size > bestCoverage.size) {
            bestInstance = candidate
            bestCoverage = thisCoverage
          }
        case None =>
      }
      trials -= 1
    }
    (bestInstance, bestCoverage)
  }

  private def getCandidate(table: Seq[(Int, ArrayBuffer[ShardedServiceInstance[T]])], numEntries: Int): Option[ShardedServiceInstance[T]] = {
    if (numEntries > 0) {
      val n = rnd.nextInt(Int.MaxValue) % numEntries
      table.find(_._1 > n).map{ case (num, instances) =>
        instances(instances.size - (num - n))
      }
    } else {
      None
    }
  }
}
