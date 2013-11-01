package com.keepit.common.store

import com.keepit.common.logging.Logging
import play.api.Play
import play.api.Play.current
import java.io.{FileOutputStream, File}
import scala.collection.mutable.HashMap
import java.nio.file.Files
import org.apache.commons.io.FileUtils

trait InMemoryObjectStore[A, B]  extends ObjectStore[A, B] with Logging {

  if (Play.isProd) throw new Exception("Can't have in memory object store in production")

  protected val localStore = new HashMap[A, B]()

  def += (kv: (A, B)) = {
    localStore += kv
    log.info(s"[+=] (${kv._1} -> ${kv._2}) localStore=$localStore")
    this
  }

  def -= (key: A) = {
    localStore -= key
    this
  }

  def get(id: A): Option[B] = localStore.get(id)

  override def toString =  s"[size=${localStore.size} keys=${localStore.keySet}"
}

trait InMemoryFileStore[A] extends ObjectStore[A, File] {

  require(!Play.isProd, "Can't have in memory file store in production")

  protected val pathMap = new HashMap[A, String]()

  def += (kv: (A, File)) = {
    val (key, file) = kv
    val copy = new File(FileUtils.getTempDirectory, file.getName)
    copy.deleteOnExit()
    val copyStream = new FileOutputStream(copy)
    Files.copy(file.toPath, copyStream)
    copyStream.close()
    pathMap += (key -> copy.getAbsolutePath)
    this
  }

  def -= (key: A) = {
    get(key).foreach(_.delete())
    pathMap -= key
    this
  }

  def get(key: A): Option[File] = pathMap.get(key).map(new File(_))

  override def toString =  s"[size=${pathMap.size} keys=${pathMap.keySet}"

}
