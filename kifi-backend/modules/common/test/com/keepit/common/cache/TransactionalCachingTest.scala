package com.keepit.common.cache

import com.keepit.common.logging.Logging
import com.keepit.serializer.{ NoCopyLocalSerializer, Serializer }
import org.specs2.mutable.Specification
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration

class TransactionalCachingTest extends Specification {

  class TestCache[K <: Key[T], T] extends ObjectCache[K, T] {
    private[this] val store = TrieMap.empty[K, Option[T]]
    val minTTL = Duration.Inf
    val maxTTL = Duration.Inf

    protected[cache] def getFromInnerCache(key: K): ObjectState[T] = {
      store.get(key) match {
        case Some(valueOpt) => Found(valueOpt)
        case None => NotFound()
      }
    }

    protected[cache] def setInnerCache(key: K, valueOpt: Option[T]) = { store += (key -> valueOpt) }

    protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, ObjectState[T]] = {
      keys.foldLeft(Map.empty[K, ObjectState[T]]) { (result, key) =>
        store.get(key) match {
          case Some(valueOpt) => result + (key -> Found(valueOpt))
          case _ => result + (key -> NotFound())
        }
      }
    }
    def remove(key: K): Unit = { store -= key }

    def exists(key: K): Boolean = store.contains(key)
    def clear(): Unit = store.clear()
  }

  case class TestKey1(id: Int) extends Key[String] {
    override val version = 1
    val namespace = "test1"
    def toKey(): String = id.toString
  }

  case class TestKey2(id: Int) extends Key[String] {
    override val version = 1
    val namespace = "test2"
    def toKey(): String = id.toString
  }

  class Cache1 extends TestCache[TestKey1, String]
  class Cache2 extends TestCache[TestKey2, String]

  private val cache1 = new Cache1
  private val cache2 = new Cache2

  class TxnCache1(c: Cache1) extends TransactionalCache(c, Serializer.string, Some(NoCopyLocalSerializer[String]))
  class TxnCache2(c: Cache2) extends TransactionalCache(c, Serializer.string)

  val txnCache1 = new TxnCache1(cache1)
  val txnCache2 = new TxnCache2(cache2)

  private val masterTxn = new TransactionalCaching with Logging {
    val isReadOnly = false
  }

  private val replicaTxn = new TransactionalCaching with Logging {
    val isReadOnly = true
  }

  "TransactionalCaching" should {
    "rollback cache data" in {
      implicit val txn = masterTxn
      txn.beginCacheTransaction()

      txnCache1.set(TestKey1(1), "foo")
      txnCache2.set(TestKey2(1), "bar")

      txnCache1.get(TestKey1(1)) === Some("foo")
      txnCache2.get(TestKey2(1)) === Some("bar")
      cache1.get(TestKey1(1)) === None
      cache2.get(TestKey2(1)) === None

      txn.rollbackCacheTransaction()

      txnCache1.get(TestKey1(1)) === None
      txnCache2.get(TestKey2(1)) === None
      cache1.get(TestKey1(1)) === None
      cache2.get(TestKey2(1)) === None
    }

    "don't write when readOnly txn" in {
      implicit val txn = replicaTxn

      txnCache1.set(TestKey1(1), "foo")
      txnCache2.set(TestKey2(1), "bar")

      txnCache1.get(TestKey1(1)) === None
      txnCache2.get(TestKey2(1)) === None
      cache1.get(TestKey1(1)) === None
      cache2.get(TestKey2(1)) === None

    }

    "commit cache data" in {
      implicit val txn = masterTxn
      txn.beginCacheTransaction()

      txnCache1.set(TestKey1(1), "foo")
      txnCache2.set(TestKey2(1), "bar")

      txnCache1.get(TestKey1(1)) === Some("foo")
      txnCache2.get(TestKey2(1)) === Some("bar")
      cache1.get(TestKey1(1)) === None
      cache2.get(TestKey2(1)) === None

      txn.commitCacheTransaction()

      txnCache1.get(TestKey1(1)) === Some("foo")
      txnCache2.get(TestKey2(1)) === Some("bar")
      cache1.get(TestKey1(1)) === Some("foo")
      cache2.get(TestKey2(1)) === Some("bar")

      txn.beginCacheTransaction()

      txnCache1.set(TestKey1(1), "foo2")
      txnCache2.set(TestKey2(1), "bar2")

      txnCache1.get(TestKey1(1)) === Some("foo2")
      txnCache2.get(TestKey2(1)) === Some("bar2")
      cache1.get(TestKey1(1)) === Some("foo")
      cache2.get(TestKey2(1)) === Some("bar")

      txn.commitCacheTransaction()

      txnCache1.get(TestKey1(1)) === Some("foo2")
      txnCache2.get(TestKey2(1)) === Some("bar2")
      cache1.get(TestKey1(1)) === Some("foo2")
      cache2.get(TestKey2(1)) === Some("bar2")
    }

    "remove cache data" in {
      implicit val txn = masterTxn
      cache1.clear()
      cache2.clear()
      cache1.set(TestKey1(1), "foo")
      cache2.set(TestKey2(1), "bar")

      txnCache1.get(TestKey1(1)) === Some("foo")
      txnCache2.get(TestKey2(1)) === Some("bar")

      txn.beginCacheTransaction()

      txnCache1.remove(TestKey1(1))

      cache1.get(TestKey1(1)) === Some("foo")
      cache2.get(TestKey2(1)) === Some("bar")
      txnCache1.get(TestKey1(1)) === None
      txnCache2.get(TestKey2(1)) === Some("bar")

      txn.rollbackCacheTransaction()

      txnCache1.get(TestKey1(1)) === Some("foo")
      txnCache2.get(TestKey2(1)) === Some("bar")

      txn.beginCacheTransaction()

      txnCache1.remove(TestKey1(1))

      txnCache1.get(TestKey1(1)) === None
      txnCache2.get(TestKey2(1)) === Some("bar")
      cache1.get(TestKey1(1)) === Some("foo")
      cache2.get(TestKey2(1)) === Some("bar")

      txn.commitCacheTransaction()

      txnCache1.get(TestKey1(1)) === None
      txnCache2.get(TestKey2(1)) === Some("bar")
      cache1.get(TestKey1(1)) === None
      cache2.get(TestKey2(1)) === Some("bar")
      cache1.exists(TestKey1(1)) === false
    }

    "bulkGet cache data" in {
      implicit val txn = masterTxn
      cache1.clear()
      cache2.clear()

      txn.beginCacheTransaction()

      txnCache1.set(TestKey1(1), "foo")
      txnCache1.set(TestKey1(2), "bar")

      txnCache1.bulkGet(Set(TestKey1(1), TestKey1(2))) === Map(TestKey1(1) -> Some("foo"), TestKey1(2) -> Some("bar"))
      txnCache1.bulkGet(Set(TestKey1(1), TestKey1(3))) === Map(TestKey1(1) -> Some("foo"), TestKey1(3) -> None)

      txn.rollbackCacheTransaction()

      txnCache1.bulkGet(Set(TestKey1(1), TestKey1(2))) === Map(TestKey1(1) -> None, TestKey1(2) -> None)

      cache1.set(TestKey1(1), "foo")

      txn.beginCacheTransaction()

      txnCache1.set(TestKey1(2), "bar")
      txnCache1.bulkGet(Set(TestKey1(1), TestKey1(2))) === Map(TestKey1(1) -> Some("foo"), TestKey1(2) -> Some("bar"))

      txn.commitCacheTransaction()

      txnCache1.bulkGet(Set(TestKey1(1), TestKey1(2))) === Map(TestKey1(1) -> Some("foo"), TestKey1(2) -> Some("bar"))

      txn.beginCacheTransaction()

      txnCache1.remove(TestKey1(1))

      txnCache1.bulkGet(Set(TestKey1(1), TestKey1(2))) === Map(TestKey1(1) -> None, TestKey1(2) -> Some("bar"))

      txn.commitCacheTransaction()

      txnCache1.bulkGet(Set(TestKey1(1), TestKey1(2))) === Map(TestKey1(1) -> None, TestKey1(2) -> Some("bar"))
      cache1.exists(TestKey1(1)) === false
    }

    "perform getOrElse" in {
      implicit val txn = masterTxn
      cache1.clear()
      txn.beginCacheTransaction()

      txnCache1.get(TestKey1(1)) === None
      txnCache1.getOrElse(TestKey1(1)) { "orElse invoked" } === "orElse invoked"
      txnCache1.get(TestKey1(1)) === Some("orElse invoked")

      txn.rollbackCacheTransaction()

      cache1.get(TestKey1(1)) === None

      txn.beginCacheTransaction()

      txnCache1.get(TestKey1(1)) === None
      txnCache1.getOrElse(TestKey1(1)) { "orElse invoked again" } === "orElse invoked again"
      txnCache1.get(TestKey1(1)) === Some("orElse invoked again")

      txn.commitCacheTransaction()

      cache1.get(TestKey1(1)) === Some("orElse invoked again")
    }

    "skip the trasactional layer in directCacheAccess method" in {
      implicit val txn = masterTxn
      cache1.clear()

      txnCache1.direct.remove(TestKey1(1))
      txnCache1.direct.remove(TestKey1(2))
      txnCache1.direct.getOrElse(TestKey1(1)) { "orElse invoked" } === "orElse invoked"

      txn.beginCacheTransaction()
      txnCache1.remove(TestKey1(1))
      txnCache1.getOrElse(TestKey1(1)) { "orElse invoked" } === "orElse invoked"
      txn.rollbackCacheTransaction()
      // changes should be gone after rollback
      txnCache1.direct.get(TestKey1(1)) === Some("orElse invoked")
      txnCache1.direct.get(TestKey1(2)) === None

      // bypass the transaction layer
      txn.beginCacheTransaction()
      txn.directCacheAccess {
        txnCache1.remove(TestKey1(1))
        txnCache1.getOrElse(TestKey1(2)) { "orElse invoked" } === "orElse invoked"
      }
      txn.rollbackCacheTransaction()
      // changes should be still there after rollback
      txnCache1.direct.get(TestKey1(1)) === None
      txnCache1.direct.get(TestKey1(2)) === Some("orElse invoked")
    }
  }
}