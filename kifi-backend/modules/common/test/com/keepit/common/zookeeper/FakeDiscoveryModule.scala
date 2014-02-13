package com.keepit.common.zookeeper

import com.keepit.common.service.ServiceType
import com.google.inject.{Singleton, Provides}
import scala.collection.mutable
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.ZooKeeper
import com.keepit.common.actor.{FakeSchedulerModule, TestActorSystemModule}
import org.apache.zookeeper.KeeperException


case class FakeDiscoveryModule() extends LocalDiscoveryModule(ServiceType.TEST_MODE) {

  def configure() {
    install(FakeSchedulerModule())
  }

  @Provides
  @Singleton
  def fakeZooKeeperClient: ZooKeeperClient = new FakeZooKeeperClient()

}

class FakeZooKeeperClient() extends ZooKeeperClient {
  private val db = new mutable.HashMap[Node, Option[Array[Byte]]]()
  def registeredCount = db.size
  def nodes = db.keys

  private val zk = new FakeZooKeeperSession(db)

  def onConnected(handler: ZooKeeperSession=>Unit): Unit = { handler(zk) }
  def session[T](f: ZooKeeperSession => T): T = f(zk)
  def close() = {}
}

class FakeZooKeeperSession(db: mutable.HashMap[Node, Option[Array[Byte]]]) extends ZooKeeperSession {

  def getState() = ZooKeeper.States.CONNECTED
  def watchNode[T](node: Node, onDataChanged : Option[T] => Unit)(implicit deserializer: Array[Byte] => T) {}
  def watchChildren(node: Node, updateChildren : Seq[Node] => Unit) {}
  def watchChildrenWithData[T](node: Node, updateChildren : Seq[(Node, T)] => Unit)(implicit deserializer: Array[Byte] => T) {}

  def create(node: Node): Node = db.synchronized {
    db(node) = None
    node
  }

  def createChild(parent: Node, name: String, createMode: CreateMode = CreateMode.PERSISTENT): Node = db.synchronized {
    val child = Node(parent, name)
    db(child) = None
    child
  }

  def createChild[T](parent: Node, name: String, data: T, createMode: CreateMode = CreateMode.PERSISTENT)(implicit serializer: T => Array[Byte]): Node = db.synchronized {
    val child = Node(parent, name)
    db(child) = None
    setData(child, data)
    child
  }

  def getChildren(node: Node): Seq[Node] = {
    db.keys.filter(entry => entry.parent.get == node).toSeq
  }

  def get(node: Node): Option[Node] = {
    if (db.contains(node)) Some(node) else None
  }

  def getData[T](node: Node)(implicit deserializer: Array[Byte] => T): Option[T] = db.synchronized {
    db.get(node) match {
      case Some(valOpt) => valOpt.map(deserializer)
      case None => throw new KeeperException.NoNodeException
    }
  }

  def setData[T](node: Node, data: T)(implicit serializer: T => Array[Byte]): Unit = db.synchronized {
    db.get(node) match {
      case Some(valOpt) => db(node) = Some(serializer(data))
      case None => throw new KeeperException.NoNodeException
    }
  }

  def deleteData(node: Node): Unit = db.synchronized {
    db.get(node) match {
      case Some(valOpt) => db(node) = None
      case None => throw new KeeperException.NoNodeException
    }
  }

  def delete(node: Node): Unit = db.synchronized {
    db.remove(node)
  }

  def deleteRecursive(node: Node): Unit = db.synchronized {
    val prefix = node.path + "/"
    val descendants = db.keySet.filter{ _.path startsWith prefix }
    descendants.foreach{ db.remove(_) }
    db.remove(node)
  }
}

