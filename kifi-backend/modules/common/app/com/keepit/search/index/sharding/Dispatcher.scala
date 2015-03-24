package com.keepit.search.index.sharding

import com.keepit.common.db.Id
import com.keepit.common.zookeeper.ServiceInstance
import scala.collection.mutable.ArrayBuffer

class ShardedServiceInstance[T](val serviceInstance: ServiceInstance) {
  val shards: Set[Shard[T]] = serviceInstance.remoteService.getShardSpec match {
    case Some(spec) => Dispatcher.shardSpecParser.parse[T](spec)
    case None => Set()
  }

  def size: Int = shards.size
}

object Dispatcher {
  private[sharding] val shardSpecParser = new ShardSpecParser

  def apply[T](instances: Vector[ServiceInstance], forceReloadFromZK: () => Unit): Dispatcher[T] = {
    new Dispatcher[T](instances.map(new ShardedServiceInstance[T](_)), forceReloadFromZK)
  }

  def defaultRandomizer: Randomizer = new Randomizer(System.currentTimeMillis() ^ Thread.currentThread().getId)

  def randomizer(seed: Long): Randomizer = new Randomizer(seed)
}

class Randomizer(seed: Long) {
  private[this] var v: Long = seed

  def nextInt(n: Int): Int = {
    v = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator
    (v % n.toLong).toInt // not aiming to be precisely random
  }
}

class Dispatcher[T](instances: Vector[ShardedServiceInstance[T]], forceReloadFromZK: () => Unit) {

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
    list.foreach { instance =>
      instance.shards.foreach { s =>
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

    instances.flatMap(_.shards.toSeq).groupBy(_.numShards).foreach {
      case (numShards, shardSeq) =>
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

  private[this] def reload(): Unit = synchronized {
    routingTable = invert()
    safeSharding = findSafeSharding()
  }

  // call a single service instance that has a shard for the sharding key
  def call[R](shardingKey: Id[T], randomizer: Randomizer)(body: (ServiceInstance) => R): R = call[R](shardingKey, body, 1, 3, randomizer)

  private def call[R](id: Id[T], body: (ServiceInstance) => R, attempts: Int, maxAttempts: Int, randomizer: Randomizer): R = {

    val table = routingTable // for safety

    val candidate: ShardedServiceInstance[T] = {
      // first choose a <shard,instances> pair. there is a single shard per sharding strategy.
      // using the reservoir algorithm
      var numEntries = 0
      var candidateShard: Shard[T] = null
      table.foreach {
        case (shard, instances) =>
          if (shard.contains(id)) {
            val size = instances.size
            numEntries += size
            if (randomizer.nextInt(numEntries) < size) candidateShard = shard
          }
      }
      if (candidateShard == null) {
        throw new DispatchFailedException(s"no shard found for id=$id")
      } else {
        // now choose an instance
        val instances = table(candidateShard)
        instances(randomizer.nextInt(instances.size))
      }
    }

    if (candidate != null && !candidate.serviceInstance.reportedSentServiceUnavailable) {
      body(candidate.serviceInstance)
    } else {
      if (attempts < maxAttempts) {
        // failed to find an instance. there may an unreachable instance. reload routingTable and try again
        reload()
        call(id, body, attempts + 1, maxAttempts, randomizer) // retry
      } else {
        forceReloadFromZK()
        throw new DispatchFailedException(s"no instance found for id=$id")
      }
    }
  }

  // find service instances to cover all shards
  def dispatch[R](randomizer: Randomizer, maxShardsPerInstance: Int)(body: (ServiceInstance, Set[Shard[T]]) => R): Seq[R] = {

    val sharding = safeSharding // for safety

    if (sharding.length > 0) {
      // choose one sharding randomly
      dispatch(sharding(randomizer.nextInt(sharding.length)), randomizer, maxShardsPerInstance)(body)
    } else {
      forceReloadFromZK()
      throw new DispatchFailedException(s"no instance found")
    }
  }

  // find service instances to cover the given set of shards
  def dispatch[R](allShards: Set[Shard[T]], randomizer: Randomizer, maxShardsPerInstance: Int = Int.MaxValue)(body: (ServiceInstance, Set[Shard[T]]) => R): Seq[R] = {
    dispatch(allShards, maxShardsPerInstance, body, new ArrayBuffer[R], 1, 3, randomizer)
  }

  private def dispatch[R](allShards: Set[Shard[T]], maxShardsPerInstance: Int, body: (ServiceInstance, Set[Shard[T]]) => R, results: ArrayBuffer[R], attempts: Int, maxAttempts: Int, randomizer: Randomizer): Seq[R] = {

    val table = routingTable // for safety

    var remaining = allShards
    while (remaining.nonEmpty) {
      next(table, remaining, maxShardsPerInstance, 2, randomizer) match {
        case (Some(instance), shards) =>
          if (!instance.serviceInstance.reportedSentServiceUnavailable) {
            results += body(instance.serviceInstance, shards)
            remaining --= shards
          } else {
            if (attempts < maxAttempts) {
              // failed to find an instance. there may an unreachable instance. reload routingTable and try again
              reload()
              return dispatch(remaining, maxShardsPerInstance, body, results, attempts + 1, maxAttempts, randomizer) // retry
            } else {
              forceReloadFromZK()
              throw new DispatchFailedException(s"no instance found")
            }
          }
        case (None, _) =>
          forceReloadFromZK()
          throw new DispatchFailedException("no instance found")
      }
    }
    results
  }

  private def next(instMap: Map[Shard[T], ArrayBuffer[ShardedServiceInstance[T]]], shards: Set[Shard[T]], maxShardsPerInstance: Int, numTrials: Int, randomizer: Randomizer): (Option[ShardedServiceInstance[T]], Set[Shard[T]]) = {
    var numEntries = 0
    val table = shards.toSeq.map { shard =>
      instMap.get(shard) match {
        case Some(instances) =>
          numEntries += instances.size
          (numEntries, instances)
        case None =>
          (numEntries, ArrayBuffer[ShardedServiceInstance[T]]())
      }
    }
    var bestInstance: Option[ShardedServiceInstance[T]] = None
    var bestInstSize: Int = Int.MaxValue
    var bestCoverage = Set.empty[Shard[T]]
    var trials = numTrials
    while (trials > 0 && bestCoverage.size < maxShardsPerInstance) {
      getCandidate(table, numEntries, randomizer) match {
        case candidate @ Some(thisInst) =>
          val thisCoverage = (thisInst.shards intersect shards).take(maxShardsPerInstance)
          if (thisCoverage.size > bestCoverage.size || (thisCoverage.size == bestCoverage.size && thisInst.size < bestInstSize)) {
            bestInstance = candidate
            bestInstSize = thisInst.size
            bestCoverage = thisCoverage
          }
        case None =>
      }
      trials -= 1
    }
    (bestInstance, bestCoverage)
  }

  private def getCandidate(table: Seq[(Int, ArrayBuffer[ShardedServiceInstance[T]])], numEntries: Int, randomizer: Randomizer): Option[ShardedServiceInstance[T]] = {
    if (numEntries > 0) {
      val n = randomizer.nextInt(numEntries)
      table.find(_._1 > n).map {
        case (num, instances) =>
          instances(instances.size - (num - n))
      }
    } else {
      None
    }
  }
}

class DispatchFailedException(msg: String) extends Exception(msg)
