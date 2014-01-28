package com.keepit.common.zookeeper

import com.keepit.common.service.ServiceType
import com.google.inject.{Singleton, Provides}
import scala.collection.mutable
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.ZooKeeper


case class FakeDiscoveryModule() extends LocalDiscoveryModule(ServiceType.TEST_MODE) {

  @Provides
  @Singleton
  def fakeZooKeeperClient: ZooKeeperClient = new FakeZooKeeperClient()

}

class FakeZooKeeperClient() extends ZooKeeperClient {
  private val db = new mutable.HashMap[Node, Array[Byte]]()
  def registeredCount = db.size
  def nodes = db.keys

  val basePath = Path("")

  private val zk = new FakeZooKeeperSession(db)

  def onConnected(handler: ZooKeeperSession=>Unit): Unit = { handler(zk) }
  def session[T](f: ZooKeeperSession => T): T = f(zk)
  def close() = {}
}

class FakeZooKeeperSession(db: mutable.HashMap[Node, Array[Byte]]) extends ZooKeeperSession {
  def getState() = ZooKeeper.States.CONNECTED
  def watchNode(node: Node, onDataChanged : Option[Array[Byte]] => Unit) {}
  def watchChildren(path: Path, updateChildren : Seq[Node] => Unit) {}
  def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T) {}
  def watchChildrenWithData[T](path: Path, watchMap: mutable.Map[Node, T], deserialize: Array[Byte] => T, notifier: Node => Unit) {}

  def create(path: Path, data: Array[Byte], createMode: CreateMode): Path = path
  def createNode(node: Node, data: Array[Byte], createMode: CreateMode): Node = {
    set(node, data)
    node
  }
  def createPath(path: Path): Path = path

  def getChildren(path: Path): Seq[Node] = Nil
  def get(node: Node): Array[Byte] = db.get(node).getOrElse(Array[Byte](0))
  def getOpt(node: Node): Option[Array[Byte]] = db.get(node)

  def set(node: Node, data: Array[Byte]) {
    db(node) = data
  }

  def delete(path: Path) { db.remove(path.asNode) }
  def deleteNode(node: Node) { db.remove(node) }
  def deleteRecursive(path: Path) { db.remove(path.asNode) }
}

