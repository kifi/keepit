package com.keepit.search.sharding

import org.specs2.mutable._
import com.keepit.common.service.{ServiceType, ServiceStatus}
import com.keepit.common.zookeeper.{RemoteService, Node, ServiceInstance}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class DispatcherTest extends Specification {

  type T = Int
  type R = (Node, Set[Shard[T]])

  class TstRemoteService(spec: String) extends RemoteService(null, ServiceStatus.UP, ServiceType.SEARCH) {
    override def healthyStatus: ServiceStatus = ServiceStatus.UP
    override def getShardSpec: Option[String] = Some(spec)
  }

  val instances = Vector[ServiceInstance](
    new ServiceInstance(Node("/a"), false, new TstRemoteService("0,1,2/12")),
    new ServiceInstance(Node("/b"), false, new TstRemoteService("3,4,5/12")),
    new ServiceInstance(Node("/c"), false, new TstRemoteService("6,7,8/12")),
    new ServiceInstance(Node("/d"), false, new TstRemoteService("9,10,11/12")),
    new ServiceInstance(Node("/e"), false, new TstRemoteService("0,1,2,3,4,5/12")),
    new ServiceInstance(Node("/f"), false, new TstRemoteService("6,7,8,9,10,11/12")),
    new ServiceInstance(Node("/g"), false, new TstRemoteService("0,3,6,9/12")),
    new ServiceInstance(Node("/h"), false, new TstRemoteService("1,4,7,10/12")),
    new ServiceInstance(Node("/i"), false, new TstRemoteService("2,5,8,11/12"))
  )
  val allShards = Set(
    Shard[T](0, 12), Shard[T](1, 12), Shard[T](2, 12),
    Shard[T](3, 12), Shard[T](4, 12), Shard[T](5, 12),
    Shard[T](6, 12), Shard[T](7, 12), Shard[T](8, 12),
    Shard[T](9, 12), Shard[T](10,12), Shard[T](11,12)
  )
  val myShardsArray = Array(
    Set(Shard[T](0, 12), Shard[T](3, 12), Shard[T](6, 12), Shard[T](9, 12)),
    Set(Shard[T](0, 12), Shard[T](1, 12), Shard[T](2, 12), Shard[T](3, 12))
  )

  "Dispatcher" should {
    "delegate the request to shards without duplicate" in {
      (0 until 10).foreach{ i =>
        var processedShards = Set.empty[Shard[T]]
        var processedTotalCount = 0
        var instanceUsed = Set.empty[ServiceInstance]
        var callCount = 0

        val disp = Dispatcher[T](instances)
        disp.dispatch(Set.empty[Shard[T]], allShards){ (inst, shards) =>
          Future.successful((inst, shards))
        }.map{ future =>
          val (inst, shards) = Await.result(future, Duration(100, "millis"))
          processedShards ++= shards
          processedTotalCount += shards.size
          instanceUsed += inst
          callCount += 1
        }

        processedShards === allShards
        processedTotalCount === allShards.size
        instanceUsed.size === callCount
      }
      1===1
    }

    "delegate the request to shards not covered by my shards" in {
      (0 to 10).foreach{ i =>
        var processedShards = Set.empty[Shard[T]]
        var processedTotalCount = 0
        var instanceUsed = Set.empty[ServiceInstance]
        var callCount = 0

        val disp = Dispatcher[T](instances)
        val myShards = myShardsArray(i % myShardsArray.size)
        disp.dispatch(myShards, allShards){ (inst, shards) =>
          Future.successful((inst, shards))
        }.map{ future =>
          val (inst, shards) = Await.result(future, Duration(100, "millis"))
          processedShards ++= shards
          processedTotalCount += shards.size
          instanceUsed += inst
          callCount += 1
        }

        processedShards === (allShards -- myShards)
        processedTotalCount === (allShards -- myShards).size
        instanceUsed.size === callCount
      }
      1===1
    }
  }
}
