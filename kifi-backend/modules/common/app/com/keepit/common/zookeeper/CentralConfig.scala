package com.keepit.common.zookeeper

import com.google.inject.{Inject, Singleton}
import org.apache.zookeeper.{CreateMode, KeeperException}


// //Sample Usage****************************************
//
// trait SampleConfigKey extends CentralConfigKey{
//   val name : String
//
//   val namespace = "test_config" //It is stronglu recommended that uou use only alphanumarics and underscores here
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

  override def toString: String

  val rootPath = "/fortytwo/config"
  def toNode : Node = Node(rootPath + "/" + toString)
}



trait BooleanCentralConfigKey extends CentralConfigKey {
  override def toString: String = s"${namespace}/boolean/${key}"
}
trait LongCentralConfigKey extends CentralConfigKey {
  override def toString: String = s"${namespace}/long/${key}"
}
trait DoubleCentralConfigKey extends CentralConfigKey {
  override def toString: String = s"${namespace}/double/${key}"
}
trait StringCentralConfigKey extends CentralConfigKey {
  override def toString: String = s"${namespace}/string/${key}"
}


trait ConfigStore {
  def get(key: CentralConfigKey): Option[String]
  def set(key: CentralConfigKey, value: String): Unit
  def watch(key: CentralConfigKey)(handler: Option[String] => Unit)
}

class ZkConfigStore(zk: ZooKeeperClient) extends ConfigStore{
  
  def get(key: CentralConfigKey): Option[String] = synchronized {
    zk.getOpt(key.toNode).map(new String(_))
  }

  def set(key: CentralConfigKey, value: String): Unit = synchronized {
    try{
      zk.set(key.toNode, value.getBytes)
    }
    catch {
      case e: KeeperException.NoNodeException => {
        try {
          zk.create(key.toNode.asPath,value.getBytes,CreateMode.PERSISTENT)
        }
        catch{
          case e: KeeperException.NoNodeException => {
            val parentPath = key.toNode.toString.split("/").tail.dropRight(1).foldLeft("")((xs,x) => xs +"/"+x)
            zk.createPath(Path(parentPath))
            zk.create(key.toNode.asPath,value.getBytes,CreateMode.PERSISTENT)
          }
        }
      }
    }
  }

  def watch(key: CentralConfigKey)(handler: Option[String] => Unit) : Unit =  synchronized {
    zk.watchNode(key.toNode, byteArrayOption => handler(byteArrayOption.map(new String(_))))
  }
}


class InMemoryConfigStore extends ConfigStore {
  import scala.collection.mutable.{HashMap, ArrayBuffer}
  val db : HashMap[String, String] = HashMap[String, String]()
  val watches : HashMap[String, ArrayBuffer[Option[String] => Unit]] = HashMap[String, ArrayBuffer[Option[String] => Unit]]()

  def get(key: CentralConfigKey): Option[String] = synchronized { 
    db.get(key.toNode.toString) 
  }

  def set(key: CentralConfigKey, value: String) : Unit = synchronized {
    db(key.toNode.toString) = value
    watches(key.toNode.toString).foreach(_(Some(value)))
  }

  def watch(key: CentralConfigKey)(handler: Option[String] => Unit) : Unit =  synchronized {
    watches(key.toNode.toString) += handler
  }

}


@Singleton
class CentralConfig @Inject() (cs: ConfigStore){

  def apply(key: BooleanCentralConfigKey) : Option[Boolean] = cs.get(key).map(_.toBoolean)
  
  def apply(key: LongCentralConfigKey) : Option[Long] = cs.get(key).map(_.toLong)
  
  def apply(key: DoubleCentralConfigKey) : Option[Double] = cs.get(key).map(_.toDouble)
  
  def apply(key: StringCentralConfigKey) : Option[String] = cs.get(key)


  def update(key: BooleanCentralConfigKey, value: Boolean) : Unit = cs.set(key, value.toString)
  
  def update(key: LongCentralConfigKey, value: Long) : Unit = cs.set(key, value.toString)

  def update(key: DoubleCentralConfigKey, value: Double) : Unit = cs.set(key, value.toString)

  def update(key: StringCentralConfigKey, value: String) : Unit = cs.set(key,value)


  def onChange(key: BooleanCentralConfigKey)(handler: Option[Boolean] => Unit) : Unit = 
    cs.watch(key){ stringValueOpt =>
      handler(stringValueOpt.map(_.toBoolean))
    }

  def onChange(key: LongCentralConfigKey)(handler: Option[Long] => Unit) : Unit = 
    cs.watch(key){ stringValueOpt =>
      handler(stringValueOpt.map(_.toLong))
    }
  
  def onChange(key: DoubleCentralConfigKey)(handler: Option[Double] => Unit) : Unit = 
    cs.watch(key){ stringValueOpt =>
      handler(stringValueOpt.map(_.toDouble))
    }
  
  def onChange(key: StringCentralConfigKey)(handler: Option[String] => Unit) : Unit = 
    cs.watch(key){ stringValueOpt =>
      handler(stringValueOpt)
    }

}



