package com.keepit.common.zookeeper

import scala.collection.mutable
import org.apache.zookeeper.{ KeeperException, CreateMode, ZooKeeper }
import play.api.libs.json.{ JsString, Json }

class FakeZooKeeperClient() extends ZooKeeperClient {
  private val db = new mutable.HashMap[Node, Option[Array[Byte]]]()
  def registeredCount = db.size
  def nodes = db.keys

  private val zk = new FakeZooKeeperSession(db)

  def onConnected(handler: ZooKeeperSession => Unit): Unit = { handler(zk) }
  def session[T](f: ZooKeeperSession => T): T = f(zk)
  def close() = {}
}

class FakeZooKeeperSession(db: mutable.HashMap[Node, Option[Array[Byte]]]) extends ZooKeeperSession {

  import com.keepit.common.strings.fromByteArray

  def getState() = ZooKeeper.States.CONNECTED
  def watchNode[T](node: Node, onDataChanged: Option[T] => Unit)(implicit deserializer: Array[Byte] => T) {}
  def watchChildren(node: Node, updateChildren: Seq[Node] => Unit) {}
  def watchChildrenWithData[T](node: Node, updateChildren: Seq[(Node, T)] => Unit)(implicit deserializer: Array[Byte] => T) {}

  def create(node: Node): Node = db.synchronized {
    db(node) = None
    node
  }

  def createChild(parent: Node, name: String, createMode: CreateMode): Node = db.synchronized {
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
    val descendants = db.keySet.filter { _.path startsWith prefix }
    descendants.foreach { db.remove(_) }
    db.remove(node)
  }

  def getSubtree(path: String): ZooKeeperSubtree = {
    val data = getData[String](Node(path)).map { s =>
      try {
        Json.parse(s)
      } catch {
        case e: Exception => JsString(s)
      }
    }

    ZooKeeperSubtree(path, data, getChildren(Node(path)).map(node => getSubtree(node.path)))
  }
}
