package com.keepit.search.sharding

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.zookeeper.ServiceInstance
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class ShardedServiceInstance[T](val serviceInstance: ServiceInstance) {
  val shards: Set[Shard[T]] = serviceInstance.remoteService.getShardSpec match {
    case Some(spec) => Dispatcher.shardSpecParser.parse[T](spec)
    case None => Set()
  }
}

object Dispatcher {
  private[sharding] val shardSpecParser = new ShardSpecParser

  def apply[T](instances: Vector[ServiceInstance], forceReload: ()=>Unit): Dispatcher[T] = {
    new Dispatcher[T](instances.map(new ShardedServiceInstance[T](_)), forceReload)
  }
}

class Dispatcher[T](instances: Vector[ShardedServiceInstance[T]], forceReload: ()=>Unit) extends Logging {

  // invert the instance list to shard->instances map filter unreachable instances out
  private def invert(): Map[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]] = {
    val upList = instances.filter(i => i.serviceInstance.isUp)
    val availableCount = instances.count(i => i.serviceInstance.isAvailable && !i.serviceInstance.reportedSentServiceUnavailable)
    val list = if (upList.length < availableCount / 2.0) {
      instances.filter(i => i.serviceInstance.isAvailable && !i.serviceInstance.reportedSentServiceUnavailable)
    } else {
      upList
    }
    var shardToInstances = Map.empty[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]]
    list.foreach{ instance =>
      instance.shards.foreach{ s =>
        shardToInstances.get(s) match {
          case Some(buf) => buf += instance
          case None => shardToInstances += (s -> ArrayBuffer(instance))
        }
      }
    }
    shardToInstances
  }

  private[this] def findSafeSharding(): ArrayBuffer[Set[Shard[T]]] = {
    val results = new ArrayBuffer[Set[Shard[T]]]

    instances.flatMap(_.shards.toSeq).groupBy(_.numShards).foreach{ case (numShards, shardSeq) =>
      val shards = shardSeq.toSet
      if (shards.size == numShards) {
        val estimatedCapacity = shardSeq.size / numShards
        var i = 0
        while (i < estimatedCapacity) {
          results += shards
          i += 1
        }
      }
    }

    results
  }

  private[this] var routingTable: Map[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]] = invert()
  private[this] var safeSharding: ArrayBuffer[Set[Shard[T]]] = findSafeSharding()

  private[this] def reload(): Unit = synchronized{
    routingTable = invert()
    safeSharding = findSafeSharding()
  }

  private[this] val rnd = new Random()

  // call a single service instance that has a shard for for id
  def call[R](id: Id[T])(body: (ServiceInstance) => R): R = call(id, body, 1, 3)

  private def call[R](id: Id[T], body: (ServiceInstance) => R, attempts: Int, maxAttempts: Int): R = {

    val table = routingTable // for safety

    var i = 0
    val candidate: ShardedServiceInstance[T] = {
      // first choose a <shard,instances> pair. there is a single shard per sharding strategy.
      // using the reservoir algorithm
      var candidateShard: Shard[T] = null
      table.foreach{ case (shard, instances) =>
        if (shard.contains(id)) {
          val size = instances.size
          i += size
          if (rnd.nextInt(i) < size) candidateShard = shard
        }
      }
      if (candidateShard == null) {
        log.error(s"no shard found for id=$id")
        throw new DispatchFailedException(s"no shard found for id=$id")
      } else {
        // now choose an instance
        val instances = table(candidateShard)
        if (instances.size > 0) instances(rnd.nextInt(instances.size)) else null
      }
    }

    if (candidate != null && !candidate.serviceInstance.reportedSentServiceUnavailable) {
      body(candidate.serviceInstance)
    } else {
      if (attempts < maxAttempts) {
        // failed to find an instance. there may an unreachable instance. reload shard->instances map and try again
        reload()
        return call(id, body, attempts + 1, maxAttempts) // retry
      } else {
        log.error(s"no instance found for id=$id")
        forceReload()
        throw new DispatchFailedException(s"no instance found for id=$id")
      }
    }
  }

  // find service instances to cover all shards
  def dispatch[R](maxShardsPerInstance: Int = Int.MaxValue)(body: (ServiceInstance, Set[Shard[T]]) => R): Seq[R] = {

    val sharding = safeSharding // for safety

    if (sharding.length > 0) {
      // choose one sharding randomly
      dispatch(sharding(rnd.nextInt(sharding.length)), maxShardsPerInstance)(body)
    } else {
      log.error(s"no instance found")
      forceReload()
      throw new DispatchFailedException(s"no instance found")
    }
  }

  // find service instances to cover the given set of shards
  def dispatch[R](allShards: Set[Shard[T]], maxShardsPerInstance: Int = Int.MaxValue)(body: (ServiceInstance, Set[Shard[T]]) => R): Seq[R] = {
    dispatch(allShards, maxShardsPerInstance, body, new ArrayBuffer[R], 1, 3)
  }

  private def dispatch[R](allShards: Set[Shard[T]], maxShardsPerInstance: Int, body: (ServiceInstance, Set[Shard[T]]) => R, results: ArrayBuffer[R], attempts: Int, maxAttempts: Int): Seq[R] = {

    val table = routingTable // for safety

    var remaining = allShards
    while (remaining.nonEmpty) {
      next(table, remaining, maxShardsPerInstance) match {
        case (Some(instance), shards) =>
          if (!instance.serviceInstance.reportedSentServiceUnavailable) {
            results += body(instance.serviceInstance, shards)
            remaining --= shards
          } else {
            if (attempts < maxAttempts) {
              // failed to find an instance. there may an unreachable instance. reload shard->instances map and try again
              reload()
              return dispatch(remaining, maxShardsPerInstance, body, results, attempts + 1, maxAttempts) // retry
            } else {
              log.error(s"no instance found")
              forceReload()
              throw new DispatchFailedException(s"no instance found")
            }
          }
        case (None, _) =>
          log.error("no instance found")
          forceReload()
          throw new DispatchFailedException("no instance found")
      }
    }
    results
  }

  private def next(instMap: Map[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]], shards: Set[Shard[T]], maxShardsPerInstance: Int, numTrials: Int = 1): (Option[ShardedServiceInstance[T]], Set[Shard[T]]) = {
    var numEntries = 0
    val table = shards.toSeq.map{ shard =>
      instMap.get(shard) match {
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
    while (trials > 0 && bestCoverage.size < maxShardsPerInstance) {
      getCandidate(table, numEntries) match {
        case candidate @ Some(inst) =>
          val thisCoverage = (inst.shards intersect shards).take(maxShardsPerInstance)
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
      val n = rnd.nextInt(numEntries)
      table.find(_._1 > n).map{ case (num, instances) =>
        instances(instances.size - (num - n))
      }
    } else {
      None
    }
  }
}

class DispatchFailedException(msg: String) extends Exception(msg)
