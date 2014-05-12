package com.keepit.search.sharding

import com.keepit.common.logging.Logging
import com.keepit.common.zookeeper.ServiceInstance
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.Random

class ShardedServiceInstance[T](val serviceInstance: ServiceInstance) {
  val shards: Set[Shard[T]] = Dispatcher.shardSpecParser.parse[T](serviceInstance.remoteService.getShardSpec)
}

object Dispatcher {
  private[sharding] val shardSpecParser = new ShardSpecParser

  def invert[T](instances: Vector[ServiceInstance]): Map[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]] = {
    var shardToInstances = Map.empty[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]]
    instances.foreach{ i =>
      val shardedServiceInstance = new ShardedServiceInstance[T](i)
      shardedServiceInstance.shards.foreach{ s =>
        shardToInstances += {
          shardToInstances.get(s) match {
            case Some(buf) => s -> (buf += shardedServiceInstance)
            case None => s -> ArrayBuffer(shardedServiceInstance)
          }
        }
      }
    }
    shardToInstances
  }

  def apply[T](instances: Vector[ServiceInstance]): Dispatcher[T] = {
    val upList = instances.filter(_.isUp)
    val availableList = instances.filter(_.isAvailable)
    val list = if (upList.length < availableList.length / 2.0) {
      availableList
    } else {
      upList
    }
    new Dispatcher[T](invert(list))
  }
}

class Dispatcher[T](shardToInstances: Map[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]]) extends Logging {
  private[this] val rnd = new Random()

  def dispatch[R](myShards: Set[Shard[T]], allShards: Set[Shard[T]])(submit: (ServiceInstance, Set[Shard[T]]) => Future[R]): Seq[Future[R]] = {
    var futures = new ArrayBuffer[Future[R]]
    var remaining = allShards -- myShards
    while (remaining.nonEmpty) {
      next(remaining) match {
        case (Some(ss), shards) =>
          remaining --= shards
          futures += submit(ss.serviceInstance, shards)
        case (None, _) =>
          throw new Exception("no instance found")
      }
    }
    futures
  }

  def next(shards: Set[Shard[T]], numTrials: Int = 1): (Option[ShardedServiceInstance[T]], Set[Shard[T]]) = {
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
