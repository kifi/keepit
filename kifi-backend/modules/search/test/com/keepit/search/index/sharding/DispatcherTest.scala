package com.keepit.search.index.sharding

import org.specs2.mutable._
import com.keepit.common.db.Id
import com.keepit.common.service.{ ServiceType, ServiceStatus }
import com.keepit.common.zookeeper.{ RemoteService, Node, ServiceInstance }

class DispatcherTest extends Specification {

  type T = Int
  type R = (Node, Set[Shard[T]])

  class TstRemoteService(spec: String) extends RemoteService(null, ServiceStatus.UP, ServiceType.SEARCH) {
    override def healthyStatus: ServiceStatus = ServiceStatus.UP
    override def getShardSpec: Option[String] = Some(spec)
  }

  val smallInstances = Vector[ServiceInstance](
    new ServiceInstance(Node("/a"), false, new TstRemoteService("0,1,2/12")),
    new ServiceInstance(Node("/b"), false, new TstRemoteService("3,4,5/12")),
    new ServiceInstance(Node("/c"), false, new TstRemoteService("6,7,8/12")),
    new ServiceInstance(Node("/d"), false, new TstRemoteService("9,10,11/12")),
    new ServiceInstance(Node("/g"), false, new TstRemoteService("0,3,6,9/12")),
    new ServiceInstance(Node("/h"), false, new TstRemoteService("1,4,7,10/12")),
    new ServiceInstance(Node("/i"), false, new TstRemoteService("2,5,8,11/12"))
  )
  val largeInstances = Vector[ServiceInstance](
    new ServiceInstance(Node("/e"), false, new TstRemoteService("0,1,2,3,4,5/12")),
    new ServiceInstance(Node("/f"), false, new TstRemoteService("6,7,8,9,10,11/12"))
  )
  val allInstances = smallInstances ++ largeInstances

  val insufficientInstances = Vector[ServiceInstance](
    new ServiceInstance(Node("/g"), false, new TstRemoteService("0,3,6,9/10")),
    new ServiceInstance(Node("/h"), false, new TstRemoteService("1,4,7/10"))
  )

  val allShards = Set(
    Shard[T](0, 12), Shard[T](1, 12), Shard[T](2, 12),
    Shard[T](3, 12), Shard[T](4, 12), Shard[T](5, 12),
    Shard[T](6, 12), Shard[T](7, 12), Shard[T](8, 12),
    Shard[T](9, 12), Shard[T](10, 12), Shard[T](11, 12)
  )
  val myShardsArray = Array(
    Set(Shard[T](0, 12), Shard[T](3, 12), Shard[T](6, 12), Shard[T](9, 12)),
    Set(Shard[T](0, 12), Shard[T](1, 12), Shard[T](2, 12), Shard[T](3, 12))
  )

  "Dispatcher" should {
    "dispatch the request to shards without duplicate" in {
      (0 until 10).foreach { i =>
        var forceReloadCalled = false
        var processedShards = Set.empty[Shard[T]]
        var processedTotalCount = 0
        var instanceUsed = Set.empty[ServiceInstance]
        var callCount = 0

        val disp = Dispatcher[T](allInstances, () => { forceReloadCalled = true })
        disp.dispatch(allShards, Dispatcher.defaultRandomizer) { (inst, shards) =>
          (inst, shards)
        }.map {
          case (inst, shards) =>
            processedShards ++= shards
            processedTotalCount += shards.size
            instanceUsed += inst
            callCount += 1
        }

        processedShards === allShards
        processedTotalCount === allShards.size
        instanceUsed.size === callCount
        forceReloadCalled === false
      }
      1 === 1
    }

    "dispatch the request with maxShardsPerInstance" in {
      var forceReloadCalled = false
      var processedShards = Set.empty[Shard[T]]
      var processedTotalCount = 0
      var instanceUsed = Set.empty[ServiceInstance]

      val disp = Dispatcher[T](largeInstances, () => { forceReloadCalled = true })
      val plan = disp.dispatch(allShards, Dispatcher.defaultRandomizer, maxShardsPerInstance = 3) { (inst, shards) =>
        (inst, shards)
      }.map {
        case (inst, shards) =>
          processedShards ++= shards
          processedTotalCount += shards.size
          instanceUsed += inst
      }

      plan.size === 4
      processedShards === allShards
      processedTotalCount === allShards.size
      instanceUsed.size === 2
      forceReloadCalled === false
    }

    "fail with insufficient instances" in {
      var forceReloadCalled = false

      val disp = Dispatcher[T](insufficientInstances, () => { forceReloadCalled = true })

      disp.dispatch(allShards, Dispatcher.defaultRandomizer, maxShardsPerInstance = 3) { (inst, shards) => 1 } must throwA[DispatchFailedException]
      forceReloadCalled === true
    }

    "call the instance with a shard contains id" in {
      var forceReloadCalled = false
      (0 until 10).foreach { i =>
        val disp = Dispatcher[T](allInstances, () => { forceReloadCalled = true })
        disp.call(Id[T](i), Dispatcher.defaultRandomizer) { inst =>
          (new ShardedServiceInstance[T](inst)).shards.exists(shard => shard.contains(Id[T](i))) === true
        }
      }
      forceReloadCalled === false
    }

    "call forceReload when no instance was found" in {
      var forceReloadCalled = false
      val disp = Dispatcher[T](Vector[ServiceInstance](), () => { forceReloadCalled = true })
      try { disp.dispatch(allShards, Dispatcher.defaultRandomizer) { (inst, shards) => 1 } } catch { case _: Throwable => }

      forceReloadCalled === true
    }

    "find safe sharding" in {
      var forceReloadCalled = false
      val disp = Dispatcher[T](smallInstances ++ insufficientInstances, () => { forceReloadCalled = true })
      var results = Set.empty[Int]

      (0 until 10).foreach { i =>
        disp.dispatch(Dispatcher.defaultRandomizer, Int.MaxValue) { (inst, shards) => results ++= shards.map(_.numShards) }
      }

      results === Set(12)
      forceReloadCalled === false
    }
  }
}
