package com.keepit.common.zookeeper

import com.google.inject.{ Inject, Singleton }
import org.apache.zookeeper.{ CreateMode, KeeperException }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.mutable.{ ArrayBuffer, SynchronizedBuffer }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.SequenceNumber
import com.keepit.common.logging.Logging
import scala.collection.JavaConversions._

// //Sample Usage****************************************
//
// trait SampleConfigKey extends CentralConfigKey{
//   val name : String
//
//   val namespace = "test_config" //Don't use funky charaters here for command line compatibility. Use "/" in the namespace to create hirachies
//   def key: String = name
// }
//
// case class SampleBooleanConfigKey(name: String) extends BooleanCentralConfigKey with SampleConfigKey
// case class SampleLongConfigKey(name: String) extends LongCentralConfigKey with SampleConfigKey
// case class SampleDoubleConfigKey(name: String) extends DoubleCentralConfigKey with SampleConfigKey
// case class SampleStringConfigKey(name: String) extends StringCentralConfigKey with SampleConfigKey
//
// //Use like a hash map
// centralConfig(SampleBooleanConfigKey("test")) = True
//
// //****************************************************

trait CentralConfigKey {
  val namespace: String
  def key: String

  def fullKey: String

  val rootPath = "/fortytwo/config"
  def toNode: Node = Node(rootPath + "/" + fullKey)

  override def toString = fullKey
}

trait BooleanCentralConfigKey extends CentralConfigKey {
  override def fullKey: String = s"${namespace}/${key}.bool"
}
trait LongCentralConfigKey extends CentralConfigKey {
  override def fullKey: String = s"${namespace}/${key}.long"
}
trait DoubleCentralConfigKey extends CentralConfigKey {
  override def fullKey: String = s"${namespace}/${key}.double"
}
trait StringCentralConfigKey extends CentralConfigKey {
  override def fullKey: String = s"${namespace}/${key}.string"
}

trait SequenceNumberCentralConfigKey[T] {
  def longKey: LongCentralConfigKey
}

trait ConfigStore {
  def get(key: CentralConfigKey): Option[String]
  def set(key: CentralConfigKey, value: String): Unit
  def watch(key: CentralConfigKey, sessionOverride: Option[ZooKeeperSession] = None)(handler: Option[String] => Unit)
}

class ZkConfigStore(zkClient: ZooKeeperClient) extends ConfigStore with Logging {
  import com.keepit.common.strings.{ fromByteArray, toByteArray }
  private[this] val watches = new java.util.concurrent.ConcurrentLinkedQueue[(CentralConfigKey, Option[String] => Unit)]()

  zkClient.onConnected { zk =>
    log.info(s"ZKX registering watches")
    watches.foreach { case (key, handler) => watch(key, Some(zk))(handler) }
  }

  def get(key: CentralConfigKey): Option[String] = zkClient.session { zk =>
    try {
      zk.getData(key.toNode)
    } catch {
      case e: KeeperException.NoNodeException => None
      case e: KeeperException.ConnectionLossException =>
        try {
          zk.getData(key.toNode)
        } catch {
          case e: KeeperException.NoNodeException => None
        }
    }
  }

  def set(key: CentralConfigKey, value: String): Unit = zkClient.session { zk =>
    try {
      zk.setData(key.toNode, value)
    } catch {
      case e: KeeperException.NoNodeException => {
        zk.create(key.toNode)
        zk.setData(key.toNode, value)
      }
    }
  }

  def watch(key: CentralConfigKey, sessionOverride: Option[ZooKeeperSession] = None)(handler: Option[String] => Unit): Unit = {
    zkClient.session { zkM =>
      val zk = sessionOverride.getOrElse(zkM)
      log.info(s"ZKX registering watch $key")
      watches.add((key, handler))
      zk.watchNode[String](key.toNode, data => SafeFuture { handler(data) })(fromByteArray)
    }
  }
}

class InMemoryConfigStore extends ConfigStore {
  import scala.collection.mutable.{ HashMap, ArrayBuffer, SynchronizedMap }
  private val db = new java.util.concurrent.ConcurrentHashMap[String, String]()
  private val watches = new java.util.concurrent.ConcurrentHashMap[String, ArrayBuffer[Option[String] => Unit]]()
  //private val watches: HashMap[String, ArrayBuffer[Option[String] => Unit]] = new HashMap[String, ArrayBuffer[Option[String] => Unit]]() with SynchronizedMap[String, ArrayBuffer[Option[String] => Unit]]

  def get(key: CentralConfigKey): Option[String] = Option(db.get(key.toNode.name))

  def set(key: CentralConfigKey, value: String): Unit = {
    db(key.toNode.name) = value
    Option(watches.get(key.toNode.name)).foreach { funs =>
      funs.foreach(_(Some(value)))
    }
  }

  def watch(key: CentralConfigKey, sessionOverride: Option[ZooKeeperSession] = None)(handler: Option[String] => Unit): Unit = {
    if (!watches.isDefinedAt(key.toNode.name)) watches(key.toNode.name) = new ArrayBuffer[Option[String] => Unit]()
    watches(key.toNode.name) += handler
  }
}

@Singleton
class CentralConfig @Inject() (cs: ConfigStore) extends Logging {

  def apply(key: BooleanCentralConfigKey): Option[Boolean] = cs.get(key).map(_.toBoolean)

  def apply(key: LongCentralConfigKey): Option[Long] = cs.get(key).map(_.toLong)

  def apply(key: DoubleCentralConfigKey): Option[Double] = cs.get(key).map(_.toDouble)

  def apply(key: StringCentralConfigKey): Option[String] = cs.get(key)

  def apply[T](key: SequenceNumberCentralConfigKey[T]): Option[SequenceNumber[T]] = apply(key.longKey).map(SequenceNumber[T](_))

  def update(key: BooleanCentralConfigKey, value: Boolean): Unit = cs.set(key, value.toString)

  def update(key: LongCentralConfigKey, value: Long): Unit = cs.set(key, value.toString)

  def update(key: DoubleCentralConfigKey, value: Double): Unit = cs.set(key, value.toString)

  def update(key: StringCentralConfigKey, value: String): Unit = cs.set(key, value)

  def update[T](key: SequenceNumberCentralConfigKey[T], value: SequenceNumber[T]): Unit = update(key.longKey, value.value)

  def onChange(key: BooleanCentralConfigKey)(handler: Option[Boolean] => Unit): Unit =
    cs.watch(key) { stringValueOpt =>
      log.info(s"ZKX Central Config watch fired on $key with boolean value $stringValueOpt")
      handler(stringValueOpt.map(_.toBoolean))
    }

  def onChange(key: LongCentralConfigKey)(handler: Option[Long] => Unit): Unit =
    cs.watch(key) { stringValueOpt =>
      log.info(s"ZKX Central Config watch fired on $key with long value $stringValueOpt")
      handler(stringValueOpt.map(_.toLong))
    }

  def onChange(key: DoubleCentralConfigKey)(handler: Option[Double] => Unit): Unit =
    cs.watch(key) { stringValueOpt =>
      log.info(s"ZKX Central Config watch fired on $key with double value $stringValueOpt")
      handler(stringValueOpt.map(_.toDouble))
    }

  def onChange(key: StringCentralConfigKey)(handler: Option[String] => Unit): Unit =
    cs.watch(key) { stringValueOpt =>
      log.info(s"ZKX Central Config watch fired on $key with string value $stringValueOpt")
      handler(stringValueOpt)
    }

  def onChange[T](key: SequenceNumberCentralConfigKey[T])(handler: Option[SequenceNumber[T]] => Unit): Unit = onChange(key.longKey) { longValueOpt: Option[Long] => handler(longValueOpt.map(SequenceNumber[T](_))) }

}

