package com.keepit.common.zookeeper

import com.keepit.common.strings._
import org.specs2.mutable.Specification
import org.apache.zookeeper.CreateMode._
import org.apache.zookeeper.KeeperException
import play.api.libs.concurrent.Execution.Implicits.{ defaultContext => execContext }
import scala.util.{ Random, Try }
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException

import LockManager._

class LockManagerTest extends Specification {

  args(skipAll = true)

  lazy val zkClient = new ZooKeeperClientImpl("localhost", 20000, Some({ zk1 => println(s"in callback, got $zk1") }))
  implicit lazy val lockMgr = new LockManager(zkClient)

  def withZKSession[T](block: (ZooKeeperSession) => T)(implicit node: Node, cleanup: Boolean = true): T = {
    println(s"starting test with root path ${node.path}")
    zkClient.session { zk =>
      try {
        zk.create(node)
        block(zk)
      } finally {
        if (cleanup) Try { zk.deleteRecursive(node) }
      }
    }
  }

  "LockManager" should {
    "creates a lock" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val lockNode = Node(node, "simplelock")
        val lock: Lock = LockManager.lock(lockNode.path).build

        zk.get(lockNode) === Some(lockNode)
        zk.getChildren(lockNode).size === 0

        lock.request(SharedMode)
        zk.getChildren(lockNode).size === 1

        lock.await(Duration(500, MILLISECONDS))
        zk.getChildren(lockNode).size === 1

        lock.request(SharedMode) must throwA[LockRequestAlreadyPlacedException]
        zk.getChildren(lockNode).size === 1

        lock.release()
        zk.getChildren(lockNode).size === 0
        lock.mode must throwA[LockRequestNotExistException]
      }
    }

    "detects duplicate lock requests" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val lockNode = Node(node, "duplicateRequest")
        val lock: Lock = LockManager.lock(lockNode.path).build

        lock.request(SharedMode)
        lock.request(SharedMode) must throwA[LockRequestAlreadyPlacedException]
        lock.request(ExclusiveMode) must throwA[LockRequestAlreadyPlacedException]
      }
    }

    "creates a shared lock" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val lockNode = Node(node, "s_lock")
        val lockA: Lock = LockManager.lock(lockNode.path).build
        val lockB: Lock = LockManager.lock(lockNode.path).build

        zk.get(lockNode) === Some(lockNode)
        zk.getChildren(lockNode).size === 0

        lockA.request(SharedMode)
        lockB.request(SharedMode)
        zk.getChildren(lockNode).size === 2

        lockA.await(Duration(500, MILLISECONDS))
        lockB.await(Duration(500, MILLISECONDS))
        zk.getChildren(lockNode).size === 2

        lockA.mode === Some(SharedMode)
        lockB.mode === Some(SharedMode)

        val lockC: Lock = LockManager.lock(lockNode.path).build
        lockC.request(ExclusiveMode)
        lockC.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]
        lockC.mode === None
        lockC.cancel()

        lockA.release()
        lockB.release()
        zk.getChildren(lockNode).size === 0
      }
    }

    "creates an exclusive lock" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val lockNode = Node(node, "x_lock")
        val lockA: Lock = LockManager.lock(lockNode.path).build
        val lockB: Lock = LockManager.lock(lockNode.path).build

        zk.get(lockNode) === Some(lockNode)
        zk.getChildren(lockNode).size === 0

        lockA.request(ExclusiveMode)
        lockB.request(ExclusiveMode)
        zk.getChildren(lockNode).size === 2

        lockA.await(Duration(500, MILLISECONDS))
        lockB.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]
        zk.getChildren(lockNode).size === 2
        lockA.mode === Some(ExclusiveMode)
        lockB.mode === None

        lockB.cancel()
        zk.getChildren(lockNode).size === 1

        lockB.request(SharedMode)
        zk.getChildren(lockNode).size === 2
        lockB.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]
        zk.getChildren(lockNode).size === 2
        lockA.mode === Some(ExclusiveMode)
        lockB.mode === None

        lockA.release()
        zk.getChildren(lockNode).size === 1

        lockB.await(Duration(500, MILLISECONDS))
        zk.getChildren(lockNode).size === 1
        lockB.mode === Some(SharedMode)

        lockB.release()
        zk.getChildren(lockNode).size === 0
      }
    }

    "calls onGranted when granted" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        val lockNode = Node(node, "lock")

        var grantedA = false
        val lockA: Lock = LockManager.lock(lockNode.path).onGranted(lck => grantedA = true).build

        lockA.request(SharedMode)
        lockA.await(Duration(500, MILLISECONDS))
        grantedA === true

        var grantedB = false
        val lockB: Lock = LockManager.lock(lockNode.path).onGranted(lck => grantedB = true).build

        lockB.request(SharedMode)
        lockB.await(Duration(500, MILLISECONDS))
        grantedB === true

        var grantedC = false
        val lockC: Lock = LockManager.lock(lockNode.path).onGranted(lck => grantedC = true).build

        lockC.request(ExclusiveMode)
        lockC.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]
        grantedC === false

        lockA.release()
        lockB.release()
        lockC.await(Duration(500, MILLISECONDS))
        grantedC === true

        grantedA = false
        grantedB = false
        lockA.request(SharedMode)
        lockB.request(SharedMode)
        lockA.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]
        lockB.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]

        lockC.release()
        lockA.await(Duration(500, MILLISECONDS))
        lockB.await(Duration(500, MILLISECONDS))
        grantedA === true
        grantedB === true

        lockA.release()
        lockB.release()

        1 === 1
      }
    }

    "calls onBlocking of shared locks when blocking" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        var blockingA = false
        var blockingB = false
        val lockNode = Node(node, "s_blocker")
        val lockA: Lock = LockManager.lock(lockNode.path).onBlocking { lck => blockingA = true }.build
        val lockB: Lock = LockManager.lock(lockNode.path).onBlocking { lck => blockingB = true }.build

        lockA.request(SharedMode)
        lockA.await(Duration(500, MILLISECONDS))

        lockB.request(SharedMode)
        lockB.await(Duration(500, MILLISECONDS))

        val lockC: Lock = LockManager.lock(lockNode.path).build
        lockC.request(ExclusiveMode)
        lockC.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]
        lockC.cancel()

        lockA.release()
        lockB.release()

        blockingA === true
        blockingB === true
      }
    }

    "creates onBlocking of exclusive locks when blocking" in {
      implicit val node = Node("/test" + Random.nextLong.abs)
      withZKSession { zk =>
        var blocking = false
        val lockNode = Node(node, "x_blocker")
        val lockA: Lock = LockManager.lock(lockNode.path).onBlocking { lck => blocking = true }.build

        lockA.request(ExclusiveMode)
        lockA.await(Duration(500, MILLISECONDS))

        val lockB: Lock = LockManager.lock(lockNode.path).build
        lockB.request(ExclusiveMode)
        lockB.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]
        lockB.mode === None
        lockB.cancel()

        blocking === true

        // there should not be the second onBlocking call
        blocking = false
        val lockC: Lock = LockManager.lock(lockNode.path).build
        lockC.request(SharedMode)
        lockC.await(Duration(500, MILLISECONDS)) must throwA[TimeoutException]
        lockC.mode === None
        lockC.cancel()
        lockA.release()

        blocking === false
      }
    }
  }
}

